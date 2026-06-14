package com.icusu.sivan.infra.memory.shared;

import com.icusu.sivan.domain.memory.IInstinctPatternRepository;
import com.icusu.sivan.domain.memory.InstinctPattern;
import com.icusu.sivan.domain.memory.ISharedTemplateRepository;
import com.icusu.sivan.domain.memory.SharedTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 共享模板服务。管理本能模板的共享、查询与质量评估。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharedTemplateService {

    private final ISharedTemplateRepository sharedTemplateRepository;
    private final IInstinctPatternRepository patternRepository;

    private static final double LOW_QUALITY_THRESHOLD = 0.3;
    private static final int LOW_QUALITY_MIN_SAMPLES = 20;
    private static final int PRIVACY_MAX_CONTINUOUS_TEXT = 50;

    public SharedTemplate share(UUID patternId, UUID ownerId, SharedTemplate.Visibility visibility) {
        Optional<InstinctPattern> opt = patternRepository.findById(patternId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("本能模板不存在: " + patternId);
        }

        InstinctPattern pattern = opt.get();
        if (!ownerId.equals(pattern.getAccountId())) {
            throw new IllegalArgumentException("只能共享自己的模板");
        }

        if (containsSensitiveContent(pattern.getTopologyJson())) {
            throw new IllegalArgumentException("模板包含敏感内容，无法共享");
        }

        SharedTemplate template = SharedTemplate.builder()
                .patternId(patternId)
                .ownerAccountId(ownerId)
                .visibility(visibility)
                .status("ACTIVE")
                .quality("NORMAL")
                .sharedAt(LocalDateTime.now())
                .build();
        sharedTemplateRepository.save(template);
        log.info("共享模板: templateId={}, patternId={}, visibility={}",
                template.getTemplateId(), patternId, visibility);
        return template;
    }

    public void unshare(UUID templateId, UUID ownerId) {
        Optional<SharedTemplate> opt = sharedTemplateRepository.findById(templateId);
        if (opt.isEmpty()) return;

        SharedTemplate template = opt.get();
        if (!ownerId.equals(template.getOwnerAccountId())) {
            throw new IllegalArgumentException("只能取消共享自己的模板");
        }
        sharedTemplateRepository.delete(templateId);
        log.info("取消共享: templateId={}", templateId);
    }

    public List<SharedTemplate> findAccessibleTemplates(UUID accountId) {
        List<SharedTemplate> result = new ArrayList<>();
        result.addAll(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.PUBLIC, accountId));
        result.addAll(sharedTemplateRepository.findByVisibilityAndNotOwner(
                SharedTemplate.Visibility.TENANT, accountId));
        result.addAll(sharedTemplateRepository.findByAllowedAccount(accountId));
        return result;
    }

    public void recordUsage(UUID templateId, boolean success) {
        Optional<SharedTemplate> opt = sharedTemplateRepository.findById(templateId);
        if (opt.isEmpty()) return;

        SharedTemplate template = opt.get();
        template.recordUsage();
        if (success) template.recordSuccess();
        sharedTemplateRepository.save(template);

        if (template.getUseCount() >= LOW_QUALITY_MIN_SAMPLES) {
            double rate = (double) template.getSuccessCount() / template.getUseCount();
            if (rate < LOW_QUALITY_THRESHOLD) {
                template.setQuality("LOW_QUALITY");
                sharedTemplateRepository.save(template);
                log.warn("共享模板质量降级: templateId={}, successRate={}/{}={}",
                        templateId, template.getSuccessCount(), template.getUseCount(),
                        String.format("%.2f", rate));
            }
        }
    }

    static boolean containsSensitiveContent(String topologyJson) {
        if (topologyJson == null || topologyJson.isBlank()) return false;

        Matcher matcher = JSON_STRING_PATTERN.matcher(topologyJson);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.length() > PRIVACY_MAX_CONTINUOUS_TEXT) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"([^\"]*)\"");
}
