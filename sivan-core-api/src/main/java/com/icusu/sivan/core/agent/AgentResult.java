package com.icusu.sivan.core.agent;

import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.TokenUsage;

import java.util.List;

public record AgentResult(String agentId, List<Msg> msgs, TokenUsage usage, boolean success, String error) {
    public static AgentResult success(String agentId, List<Msg> msgs, TokenUsage usage) {
        return new AgentResult(agentId, List.copyOf(msgs), usage, true, null);
    }

    public static AgentResult failure(String agentId, String error) {
        return new AgentResult(agentId, List.of(), TokenUsage.EMPTY, false, error);
    }

    /** 最后一条 assistant 消息的正文文本。 */
    public String content() {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            var m = msgs.get(i);
            if (m.role() == Role.ASSISTANT) {
                String text = m.text();
                if (text != null && !text.isBlank()) return text;
            }
        }
        return "";
    }

    /** 最后一条 assistant 消息的思考内容。 */
    public String thinking() {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            var m = msgs.get(i);
            if (m.role() == Role.ASSISTANT) {
                String thinking = m.thinking();
                if (thinking != null && !thinking.isBlank()) return thinking;
            }
        }
        return "";
    }
}
