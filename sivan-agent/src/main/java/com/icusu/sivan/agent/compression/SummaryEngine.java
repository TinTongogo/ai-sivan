package com.icusu.sivan.agent.compression;

import com.icusu.sivan.agent.model.DefaultModelRouter;
import com.icusu.sivan.core.message.Content;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import com.icusu.sivan.domain.conversation.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 摘要引擎 — 将一组消息缩减为摘要。
 * <p>
 * 设计文档 4.3 节。L2 单 turn 摘要，L3 多 turn 主题聚合。
 */
@Component
public class SummaryEngine {

    private static final Logger log = LoggerFactory.getLogger(SummaryEngine.class);

    private final DefaultModelRouter modelRouter;

    public SummaryEngine(DefaultModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    /** L2 摘要：单个 turn 的摘要。 */
    public String summarizeTurn(List<Message> turn, UUID accountId) {
        String text = turn.stream()
                .map(m -> (m.getRole() != null ? m.getRole() : "unknown") + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (estimateTokens(text) < 50) return text; // 太短，不摘要

        try {
            Model model = modelRouter.getDefaultModel(accountId);
            List<Msg> messages = List.of(
                    Msg.of(Role.SYSTEM, List.of(new Content.Text(
                            "你是一个对话摘要助手。将以下对话压缩为 1-2 句话，保留关键信息。"))),
                    Msg.of(Role.USER, List.of(new Content.Text(text)))
            );
            return model.chat(messages, List.of(), Model.ModelParams.defaults())
                    .block()
                    .msg()
                    .text();
        } catch (Exception e) {
            log.warn("[摘要] L2 摘要失败: {}", e.getMessage());
            return text;
        }
    }

    /** L3 摘要：同一主题的多轮对话的聚合摘要。 */
    public String summarizeTopic(List<List<Message>> turns, UUID accountId) {
        StringBuilder sb = new StringBuilder();
        for (var turn : turns) {
            sb.append(summarizeTurn(turn, accountId)).append("\n");
        }
        String text = sb.toString();
        if (estimateTokens(text) > 500) {
            // 递归压缩
            Message combined = new Message();
            combined.setContent(text);
            combined.setRole("system");
            return summarizeTurn(List.of(combined), accountId);
        }
        return text;
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }
}
