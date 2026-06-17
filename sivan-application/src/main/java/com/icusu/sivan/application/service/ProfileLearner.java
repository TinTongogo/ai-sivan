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

            model.chat(llmMessages, List.of(), Model.ModelParams.defaults().withTemperature(0.1))
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
     */
    static List<String> parseExpertise(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return Collections.emptyList();
        String trimmed = jsonText.trim();
        // 去掉可能的 markdown 代码块标记
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf("{");
            int end = trimmed.lastIndexOf("}");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(trimmed,
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
        } catch (Exception e) {
            log.debug("[ProfileLearner] JSON 解析失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
