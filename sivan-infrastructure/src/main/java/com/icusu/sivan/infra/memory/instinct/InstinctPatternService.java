package com.icusu.sivan.infra.memory.instinct;

import com.icusu.sivan.domain.feedback.FeatureDeviation;
import com.icusu.sivan.domain.feedback.PatternFeedbackRecord;
import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.domain.task.PatternFeatureVector;
import com.icusu.sivan.domain.task.TaskFeatures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本能模板服务。
 * 管理特征驱动的任务执行路径元模板，支持 CHAT / SINGLE_AGENT / SQUAD 三种执行形态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstinctPatternService {

    private final IInstinctPatternRepository patternRepository;
    private final ISharedTemplateRepository sharedTemplateRepository;

    private final ConcurrentHashMap<UUID, byte[]> lockPool = new ConcurrentHashMap<>();

    static final double MATCH_THRESHOLD = 0.65;
    private static final double AUTO_ACTIVATE_THRESHOLD = 0.75;

    public Optional<InstinctPattern> match(TaskFeatures features, UUID accountId) {
        Optional<InstinctPattern> ownMatch = matchOwn(features, accountId);
        if (ownMatch.isPresent()) {
            return ownMatch;
        }
        Optional<InstinctPattern> sharedMatch = matchShared(features, accountId);
        if (sharedMatch.isPresent()) {
            log.info("共享池匹配成功: 为 accountId={} 创建本地副本", accountId);
            return sharedMatch;
        }
        return Optional.empty();
    }

    private Optional<InstinctPattern> matchOwn(TaskFeatures features, UUID accountId) {
        List<InstinctPattern> activePatterns = patternRepository.findActiveByAccount(accountId);
        if (activePatterns.isEmpty()) return Optional.empty();

        InstinctPattern bestMatch = null;
        double bestScore = 0;

        for (InstinctPattern pattern : activePatterns) {
            if (pattern.getFeatureVector() == null) continue;
            double score = pattern.getFeatureVector().matchScore(features);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = pattern;
            }
        }

        if (bestMatch != null && bestScore >= MATCH_THRESHOLD) {
            synchronized (lockPool.computeIfAbsent(bestMatch.getPatternId(), k -> new byte[0])) {
                InstinctPattern reloaded = patternRepository.findById(bestMatch.getPatternId()).orElse(null);
                if (reloaded != null) {
                    reloaded.recordHit();
                    patternRepository.update(reloaded);
                    log.debug("模板匹配成功: patternId={}, score={}", reloaded.getPatternId(),
                            String.format("%.3f", bestScore));
                    return Optional.of(reloaded);
                }
                return Optional.of(bestMatch);
            }
        }
        log.debug("无匹配模板: bestScore={}", String.format("%.3f", bestScore));
        return Optional.empty();
    }

    private Optional<InstinctPattern> matchShared(TaskFeatures features, UUID accountId) {
        List<SharedTemplate> accessible = findAccessibleSharedTemplates(accountId);
        if (accessible.isEmpty()) return Optional.empty();

        InstinctPattern bestMatch = null;
        double bestScore = 0;
        boolean bestIsLowQuality = false;

        for (SharedTemplate st : accessible) {
            if (st.getPatternId() == null) continue;
            Optional<InstinctPattern> patternOpt = patternRepository.findById(st.getPatternId());
            if (patternOpt.isEmpty()) continue;

            InstinctPattern pattern = patternOpt.get();
            if (pattern.getFeatureVector() == null) continue;

            double score = pattern.getFeatureVector().matchScore(features);
            boolean isLowQuality = "LOW_QUALITY".equals(st.getQuality());

            boolean better = false;
            if (bestMatch == null) {
                better = true;
            } else if (!isLowQuality && bestIsLowQuality) {
                better = true;
            } else if (isLowQuality == bestIsLowQuality && score > bestScore) {
                better = true;
            }

            if (better) {
                bestMatch = pattern;
                bestScore = score;
                bestIsLowQuality = isLowQuality;
            }
        }

        if (bestMatch != null && bestScore >= MATCH_THRESHOLD) {
            return Optional.of(createLocalCopy(bestMatch, accountId));
        }
        return Optional.empty();
    }

    private InstinctPattern createLocalCopy(InstinctPattern source, UUID accountId) {
        InstinctPattern copy = InstinctPattern.builder()
                .accountId(accountId)
                .featureVector(source.getFeatureVector())
                .executionMode(source.getExecutionMode())
                .topologyJson(source.getTopologyJson())
                .sourcePatternId(source.getPatternId())
                .version(1)
                .hitCount(0)
                .successCount(0)
                .totalCount(0)
                .active(true)
                .build();
        patternRepository.save(copy);
        log.info("从共享池创建本地副本: localPatternId={}, sourcePatternId={}",
                copy.getPatternId(), source.getPatternId());
        return copy;
    }

    private List<SharedTemplate> findAccessibleSharedTemplates(UUID accountId) {
        List<SharedTemplate> result = new ArrayList<>();
        result.addAll(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId));
        result.addAll(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId));
        result.addAll(sharedTemplateRepository.findByAllowedAccount(accountId));
        return result;
    }

    public List<InstinctPattern> findMatchable(TaskFeatures features, UUID accountId) {
        List<InstinctPattern> activePatterns = patternRepository.findActiveByAccount(accountId);
        return activePatterns.stream()
                .filter(p -> p.getFeatureVector() != null)
                .sorted(Comparator.<InstinctPattern, Double>comparing(
                        p -> p.getFeatureVector().matchScore(features), Comparator.reverseOrder()))
                .toList();
    }

    public InstinctPattern freeze(UUID accountId, String topologyJson,
                                   PatternFeatureVector features, String executionMode) {
        InstinctPattern pattern = InstinctPattern.builder()
                .accountId(accountId)
                .topologyJson(topologyJson)
                .featureVector(features)
                .executionMode(executionMode)
                .version(1)
                .successCount(1)
                .totalCount(1)
                .active(false)
                .build();
        patternRepository.save(pattern);
        log.info("冻结本能模板: patternId={}, executionMode={}", pattern.getPatternId(), executionMode);
        return pattern;
    }

    public void recordResult(UUID patternId, boolean success, TaskFeatures features) {
        synchronized (lockPool.computeIfAbsent(patternId, k -> new byte[0])) {
            Optional<InstinctPattern> opt = patternRepository.findById(patternId);
            if (opt.isEmpty()) return;

            InstinctPattern pattern = opt.get();
            int total = (pattern.getTotalCount() != null ? pattern.getTotalCount() : 0) + 1;
            int successCount = pattern.getSuccessCount() != null ? pattern.getSuccessCount() : 0;
            if (success) successCount++;
            pattern.setTotalCount(total);
            pattern.setSuccessCount(successCount);

            if (success && features != null && pattern.getFeatureVector() != null) {
                PatternFeatureVector merged = pattern.getFeatureVector().merge(features, 0.1);
                pattern.setFeatureVector(merged);
            }

            double rate = (double) successCount / total;
            if (rate >= AUTO_ACTIVATE_THRESHOLD && !Boolean.TRUE.equals(pattern.getActive())) {
                pattern.setActive(true);
                log.info("本能模板自动激活: patternId={}, 成功率={}", patternId, rate);
            }

            patternRepository.update(pattern);
        }
    }

    public void recordResult(UUID patternId, boolean success) {
        recordResult(patternId, success, null);
    }

    private static final long DEDUP_WINDOW_MINUTES = 5;
    private static final double DEDUP_SCORE_THRESHOLD = 0.9;

    public void processFeedback(PatternFeedbackRecord feedback, String topologyJson) {
        if (feedback.getOutcome() != PatternFeedbackRecord.FeedbackOutcome.SUCCESS) return;
        if (!PatternFeedbackRecord.FeedbackSource.TRIGGER_LLM.name().equals(feedback.getSource())) return;
        if (feedback.getActualFeatures() == null) return;

        if (feedback.getPatternId() == null) {
            handleT1(feedback, topologyJson);
        } else {
            handleT2(feedback, topologyJson);
        }
    }

    private void handleT1(PatternFeedbackRecord feedback, String topologyJson) {
        synchronized (lockPool.computeIfAbsent(feedback.getAccountId(), k -> new byte[0])) {
            if (isDuplicate(feedback)) return;

            TaskFeatures features = feedback.getActualFeatures();
            InstinctPattern newPattern = InstinctPattern.builder()
                    .accountId(feedback.getAccountId())
                    .featureVector(PatternFeatureVector.fromTaskFeatures(features))
                    .executionMode(detectExecutionMode(features))
                    .topologyJson(topologyJson)
                    .hitCount(1)
                    .successCount(1)
                    .totalCount(1)
                    .version(1)
                    .active(true)
                    .build();
            patternRepository.save(newPattern);
            log.info("T1 创建新模板: patternId={}, executionMode={}, features={}",
                    newPattern.getPatternId(), newPattern.getExecutionMode(), features);
        }
    }

    private void handleT2(PatternFeedbackRecord feedback, String topologyJson) {
        FeatureDeviation deviation = feedback.getDeviation();
        if (deviation == null || deviation.mismatchDimensions().isEmpty()) return;

        synchronized (lockPool.computeIfAbsent(feedback.getPatternId(), k -> new byte[0])) {
            Optional<InstinctPattern> opt = patternRepository.findById(feedback.getPatternId());
            if (opt.isEmpty()) return;

            InstinctPattern oldPattern = opt.get();
            if (isDuplicate(feedback)) return;

            InstinctPattern newVersion = oldPattern.newVersion();
            newVersion.setTopologyJson(topologyJson);

            TaskFeatures features = feedback.getActualFeatures();
            if (oldPattern.getFeatureVector() != null && features != null) {
                newVersion.setFeatureVector(oldPattern.getFeatureVector().merge(features, 0.3));
            } else if (features != null) {
                newVersion.setFeatureVector(PatternFeatureVector.fromTaskFeatures(features));
            }

            patternRepository.save(newVersion);
            log.info("T2 创建模板新版本: oldPatternId={}, newVersion={}, mismatchDimensions={}",
                    oldPattern.getPatternId(), newVersion.getVersion(), deviation.mismatchDimensions());
        }
    }

    private boolean isDuplicate(PatternFeedbackRecord feedback) {
        if (feedback.getActualFeatures() == null) return false;

        LocalDateTime since = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
        List<InstinctPattern> recent = patternRepository
                .findByAccountIdAndCreatedAtAfter(feedback.getAccountId(), since);

        for (InstinctPattern p : recent) {
            if (p.getFeatureVector() != null
                    && p.getFeatureVector().matchScore(feedback.getActualFeatures()) >= DEDUP_SCORE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private static String detectExecutionMode(TaskFeatures features) {
        if (features == null) return "SQUAD";
        return switch (features.complexity()) {
            case LEVEL_1 -> "CHAT";
            case LEVEL_2 -> "SINGLE_AGENT";
            case LEVEL_3, LEVEL_4, LEVEL_5 -> "SQUAD";
        };
    }

}
