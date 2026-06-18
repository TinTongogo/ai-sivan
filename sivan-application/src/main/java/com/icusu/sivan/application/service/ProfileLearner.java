package com.icusu.sivan.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.agent.prompt.ProfilePrompts;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.account.IProfileChangeLogRepository;
import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.ProfileChangeLog;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.domain.conversation.IMessageRepository;
import com.icusu.sivan.domain.conversation.Message;
import com.icusu.sivan.domain.shared.event.MessageCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 画像自动学习器 — 从对话中提取技术栈/兴趣标签，增量更新用户画像。
 * <p>
 * 监听 {@link MessageCompletedEvent}，异步调用 LLM 提取标签，
 * 通过 {@link UserProfile#mergeLearningSignal} 增量合并到画像。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileLearner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IUserProfileRepository profileRepository;
    private final IProfileChangeLogRepository changeLogRepository;
    private final IMessageRepository messageRepository;
    private final ModelRouter modelRouter;

    @Async
    @EventListener
    public void onMessageCompleted(MessageCompletedEvent event) {
        UUID accountId = event.accountId();
        UUID conversationId = event.conversationId();

        try {
            // 1. 查找画像，检查自动学习开关
            var profileOpt = profileRepository.findByAccountId(accountId);
            if (profileOpt.isEmpty() || !profileOpt.get().isAutoLearn()) {
                log.debug("[ProfileLearner] 跳过: accountId={} autoLearn={}",
                        accountId, profileOpt.map(UserProfile::isAutoLearn).orElse(null));
                return;
            }
            UserProfile profile = profileOpt.get();

            // 2. 获取对话最近消息
            List<Message> messages = messageRepository.findByConversationId(conversationId);
            if (messages.size() < 2) {
                log.debug("[ProfileLearner] 消息不足, 跳过: conversationId={}", conversationId);
                return;
            }
            int fromIndex = Math.max(0, messages.size() - 10);
            List<Message> recent = messages.subList(fromIndex, messages.size());
            String conversationText = recent.stream()
                    .map(m -> (m.isUser() ? "用户：" : "助手：") + m.getContent())
                    .reduce("", (a, b) -> a + b + "\n");

            // 3. 调用 LLM 提取标签（响应式，无 .block()）
            Model model = modelRouter.getDefaultModel(accountId);
            List<Msg> llmMessages = List.of(
                    Msg.of(Role.SYSTEM, ProfilePrompts.EXTRACT_SYSTEM),
                    Msg.of(Role.USER, conversationText)
            );

            model.chat(llmMessages, List.of(), Model.ModelParams.defaults()
                            .withTemperature(0.1)
                            .withExtra("response_format", Map.of("type", "json_object")))
                    .subscribe(response -> {
                        List<String> tags = parseExpertise(response != null ? response.msg().text() : null);
                        if (tags.isEmpty()) {
                            log.debug("[ProfileLearner] 未提取到标签: conversationId={}", conversationId);
                            return;
                        }
                        log.info("[ProfileLearner] 提取标签: accountId={} tags={}", accountId, tags);
                        try {
                            // 记录变更前的值
                            String oldValue = profile.getExpertise() != null
                                    ? String.join(", ", profile.getExpertise()) : null;
                            // 合并到画像
                            profile.mergeLearningSignal(tags, null);
                            profileRepository.save(profile);
                            // 记录变更日志
                            String newValue = String.join(", ", profile.getExpertise());
                            changeLogRepository.save(ProfileChangeLog.of(accountId, "auto_learn",
                                    "expertise", oldValue, newValue));
                            log.info("[ProfileLearner] 画像已更新: accountId={} newTags={}", accountId, tags);
                        } catch (Exception e) {
                            log.warn("[ProfileLearner] 画像更新失败: {}", e.getMessage());
                        }
                    }, e -> log.warn("[ProfileLearner] LLM 调用异常: conversationId={}, {}",
                            conversationId, e.getMessage()));

        } catch (Exception e) {
            log.warn("[ProfileLearner] 学习异常: conversationId={}, {}", conversationId, e.getMessage());
        }
    }

    /**
     * 从 LLM 响应中解析 expertise 数组。
     * 优先直接解析 JSON；失败时尝试正则提取 JSON 对象后重试。
     */
    static List<String> parseExpertise(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return Collections.emptyList();

        // 1. 清理输入：去掉 markdown 代码块标记及前后空白
        String cleaned = stripMarkdownFence(jsonText);

        // 2. 尝试直接解析
        List<String> result = tryParseJson(cleaned);
        if (!result.isEmpty()) return result;

        // 3. 直接解析失败，尝试正则提取最外层 {…} JSON
        String extracted = extractJsonObject(cleaned);
        if (extracted != null) {
            result = tryParseJson(extracted);
            if (!result.isEmpty()) return result;
        }

        log.debug("[ProfileLearner] JSON 解析失败: raw={}", truncate(jsonText, 120));
        return Collections.emptyList();
    }

    /** 去掉 markdown 代码块标记（```json ... ``` 或 ``` ... ```）。 */
    private static String stripMarkdownFence(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            // 去掉首行 ``` 标记
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0 && firstNewline < s.length() - 1) {
                s = s.substring(firstNewline + 1).trim();
            }
            // 去掉末尾 ``` 标记
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        return s;
    }

    /** 尝试以 Map 形式解析 JSON，提取 expertise 数组。 */
    private static List<String> tryParseJson(String text) {
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(text,
                    new TypeReference<Map<String, Object>>() {});
            Object expertiseObj = map.get("expertise");
            if (expertiseObj instanceof List<?> list) {
                List<String> tags = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) {
                        tags.add(s.trim());
                    }
                }
                return tags;
            }
        } catch (Exception ignored) {
            // fall through to next fallback
        }
        return Collections.emptyList();
    }

    /** 从文本中正则提取最外层 {…} JSON 对象。 */
    private static String extractJsonObject(String text) {
        // 找到第一个 { 和最后一个 }，尝试提取中间的 JSON
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String candidate = text.substring(start, end + 1);
            // 快速校验：花括号基本平衡（简单计数）
            int open = 0;
            boolean valid = true;
            for (int i = 0; i < candidate.length(); i++) {
                char c = candidate.charAt(i);
                if (c == '{') open++;
                else if (c == '}') open--;
                if (open < 0) { valid = false; break; }
            }
            if (valid && open == 0) return candidate;
        }
        return null;
    }

    /** 截断长文本用于日志输出。 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
