package com.icusu.sivan.application.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.prompt.ChatPrompts;
import com.icusu.sivan.common.util.CosineSimilarity;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.domain.conversation.Conversation;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.infra.memory.flashback.FlashbackScanner;
import com.icusu.sivan.application.conversation.tree.ContextBuilder;
import com.icusu.sivan.application.conversation.tree.ContextCache;
import com.icusu.sivan.application.conversation.tree.ContextResult;
import com.icusu.sivan.application.knowledge.RagContextBuilder;
import com.icusu.sivan.application.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 上下文组装服务 — 将历史消息、用户画像、情境闪现、文件快照、RAG 整合为 LLM 输入。
 * <p>
 * 从 {@link PromptContextService} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssemblyService {

    private final ContextBuilder contextBuilder;
    private final ContextCache contextCache;
    private final IUserProfileRepository userProfileRepository;
    private final IAccountRepository accountRepository;
    private final IEmbeddingService embeddingService;
    private final GroupService groupService;
    private final FlashbackScanner flashbackScanner;
    private final RagContextBuilder ragContextBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ====== Epoch 上下文 ======

    public String buildEpochContext(UUID conversationId,
                                    com.icusu.sivan.domain.conversation.CompressResult compressResult,
                                    int historyBudget, List<Message> allMessages) {
        try {
            ContextResult epochResult = contextBuilder.buildEpochs(
                    conversationId, compressResult, historyBudget, allMessages);
            epochResult = contextCache.apply(conversationId, epochResult);
            if (epochResult != null && !epochResult.isEmpty()) {
                return epochResult.toFlatString();
            }
        } catch (Exception e) {
            log.warn("ContextBuilder 增强失败，降级到压缩摘要", e);
        }
        return compressResult.toSummaryText();
    }

    public ContextResult buildEpochContextResult(UUID conversationId,
                                                  com.icusu.sivan.domain.conversation.CompressResult compressResult,
                                                  int historyBudget, List<Message> allMessages) {
        ContextResult epochResult = allMessages != null
                ? contextBuilder.buildEpochs(conversationId, compressResult, historyBudget, allMessages)
                : contextBuilder.buildEpochs(conversationId, compressResult, historyBudget);
        epochResult = contextCache.apply(conversationId, epochResult);
        return epochResult;
    }

    // ====== 用户画像 ======

    public String buildUserProfileSection(UUID accountId) {
        try {
            var profileOpt = userProfileRepository.findByAccountId(accountId);
            if (profileOpt.isPresent()) {
                return formatProfileSection(profileOpt.get(), profileOpt.get().getExpertise());
            }
            var accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isPresent() && accountOpt.get().getPreferences() != null) {
                return formatProfileFromPreferences(accountOpt.get().getPreferences());
            }
        } catch (Exception e) {
            log.debug("构建用户画像失败: {}", e.getMessage());
        }
        return null;
    }

    public String buildUserProfileSection(UUID accountId, String queryContext) {
        if (queryContext == null || queryContext.isBlank() || !embeddingService.isAvailable()) {
            return buildUserProfileSection(accountId);
        }
        try {
            var profileOpt = userProfileRepository.findByAccountId(accountId);
            if (profileOpt.isEmpty()) return buildUserProfileSection(accountId);

            UserProfile profile = profileOpt.get();
            if (profile.getExpertise() == null || profile.getExpertise().isEmpty()) {
                return buildUserProfileSection(accountId);
            }

            float[] queryVec = embeddingService.embed(queryContext);
            if (queryVec == null) return buildUserProfileSection(accountId);

            List<String> allTags = profile.getExpertise();
            List<float[]> tagVecs = embeddingService.embedBatch(allTags);

            var scored = new ArrayList<ScoredTag>();
            for (int i = 0; i < allTags.size() && i < tagVecs.size(); i++) {
                float[] tagVec = tagVecs.get(i);
                if (tagVec != null) {
                    double sim = CosineSimilarity.compute(queryVec, tagVec);
                    scored.add(new ScoredTag(allTags.get(i), sim));
                }
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));

            var topTags = scored.stream()
                    .limit(3)
                    .map(s -> s.tag)
                    .toList();
            return formatProfileSection(profile, topTags);
        } catch (Exception e) {
            log.debug("语义过滤画像失败，使用完整画像: {}", e.getMessage());
            return buildUserProfileSection(accountId);
        }
    }

    public static String formatProfileSection(UserProfile profile, List<String> topTags) {
        StringBuilder p = new StringBuilder("## 用户信息\n");
        boolean hasProfile = false;
        if (profile.getName() != null && !profile.getName().isBlank()) {
            p.append("- 称呼：").append(profile.getName()).append("\n");
            hasProfile = true;
        }
        if (profile.getBio() != null && !profile.getBio().isBlank()) {
            p.append("- 简介：").append(profile.getBio()).append("\n");
            hasProfile = true;
        }
        if (profile.getAiLanguage() != null && !profile.getAiLanguage().isBlank()
                && !"auto".equals(profile.getAiLanguage())) {
            p.append("- 回复语言：").append(profile.getAiLanguage()).append("\n");
            hasProfile = true;
        }
        List<String> tags = topTags != null ? topTags : profile.getExpertise();
        if (tags != null && !tags.isEmpty()) {
            p.append("- 技术栈：").append(String.join("、", tags)).append("\n");
            hasProfile = true;
        }
        return hasProfile ? p.toString() : null;
    }

    public String formatProfileFromPreferences(String preferencesJson) {
        try {
            var prefs = objectMapper.readTree(preferencesJson);
            StringBuilder p = new StringBuilder("## 用户信息\n");
            boolean hasProfile = false;
            if (prefs.has("name") && !prefs.get("name").asText().isBlank()) {
                p.append("- 称呼：").append(prefs.get("name").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("bio") && !prefs.get("bio").asText().isBlank()) {
                p.append("- 简介：").append(prefs.get("bio").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("aiLanguage") && !prefs.get("aiLanguage").asText().isBlank()) {
                p.append("- 回复语言：").append(prefs.get("aiLanguage").asText()).append("\n");
                hasProfile = true;
            }
            if (prefs.has("expertise") && prefs.get("expertise").isArray()) {
                var arr = prefs.get("expertise");
                var items = new ArrayList<String>();
                for (int i = 0; i < arr.size(); i++) items.add(arr.get(i).asText());
                p.append("- 技术栈：").append(String.join("、", items)).append("\n");
                hasProfile = true;
            }
            return hasProfile ? p.toString() : null;
        } catch (Exception e) {
            log.debug("解析 preferences JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    // ====== 情境闪现 ======

    public String buildFlashbackSection(UUID accountId, String context) {
        try {
            var candidates = flashbackScanner.scan(accountId, context, 5);
            if (candidates == null || candidates.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("## 回忆闪现\n");
            sb.append("以下是与当前话题相关的过往记忆，请留意它们是否对当前对话有帮助：\n");
            int count = 0;
            for (var c : candidates) {
                if (c.getRelevanceScore() <= 0) continue;
                String content = c.getContent();
                if (content == null || content.isBlank()) continue;
                String line = content.length() > 150 ? content.substring(0, 147) + "..." : content;
                String level = switch (c.getLevel()) {
                    case SESSION -> "会话";
                    case USER -> "长期";
                    case TEAM -> "团队";
                    case PROJECT -> "项目";
                };
                sb.append("- [").append(level).append("] ").append(line).append("\n");
                count++;
            }
            if (count == 0) return null;
            return sb.toString();
        } catch (Exception e) {
            log.debug("情境闪现构建失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    // ====== 文件快照 ======

    public String buildFileSnapshot(UUID accountId, UUID projectId) {
        if (projectId == null) return null;
        try {
            String rootPath = groupService.getProjectRootPath(accountId, projectId);
            if (rootPath == null) return null;
            Path root = Paths.get(rootPath);
            if (!Files.exists(root)) return null;
            String displayPath = groupService.getProjectDisplayPath(accountId, projectId);

            List<Path> recentFiles = new ArrayList<>();
            int fileCount = 0;
            int dirCount = 0;

            try (Stream<Path> stream = Files.walk(root, 3)) {
                var it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (p.equals(root)) continue;
                    if (Files.isDirectory(p)) {
                        dirCount++;
                    } else if (Files.isRegularFile(p)) {
                        fileCount++;
                        recentFiles.add(p);
                    }
                }
            }

            recentFiles.sort((a, b) -> {
                try {
                    long ta = Files.getLastModifiedTime(a).toMillis();
                    long tb = Files.getLastModifiedTime(b).toMillis();
                    return Long.compare(tb, ta);
                } catch (IOException e) {
                    return 0;
                }
            });
            if (recentFiles.size() > 5) recentFiles = recentFiles.subList(0, 5);

            StringBuilder sb = new StringBuilder("## 工作目录状态\n");
            sb.append("路径：").append(displayPath).append("\n");
            sb.append("文件：").append(fileCount).append(" 个文件，").append(dirCount).append(" 个目录\n");
            if (!recentFiles.isEmpty()) {
                sb.append("最近修改：\n");
                long now = System.currentTimeMillis();
                for (Path f : recentFiles) {
                    String rel = root.relativize(f).toString();
                    long lastMod = Files.getLastModifiedTime(f).toMillis();
                    long diffMin = (now - lastMod) / 60000;
                    String timeAgo = diffMin < 1 ? "刚刚" : diffMin + " 分钟前";
                    sb.append("  ").append(rel).append("（").append(timeAgo).append("）\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("构建文件状态快照失败(不影响主流程): {}", e.getMessage());
            return null;
        }
    }

    // ====== 项目上下文 ======

    public String buildProjectHint(Conversation conversation, UUID accountId) {
        UUID projectId = conversation.getProjectId();
        if (projectId == null) {
            log.warn("对话缺少 projectId: conversationId={}", conversation.getConversationId());
            return null;
        }
        try {
            var project = groupService.findOwned(accountId, projectId);
            return ChatPrompts.projectContextHint(project.getName()).content();
        } catch (Exception e) {
            log.warn("解析项目上下文失败: accountId={}, projectId={}", accountId, projectId, e);
            return null;
        }
    }

    // ====== RAG ======

    public String buildRagContext(String query, Conversation conversation, UUID accountId) {
        return ragContextBuilder.build(query, conversation, accountId);
    }

    // ====== 内部类型 ======

    public record ScoredTag(String tag, double score) {}
}
