package com.icusu.sivan.core.model;

import com.icusu.sivan.core.tool.ToolSpec;
import com.icusu.sivan.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface Model {

    String modelId();

    default Mono<ModelResponse> chat(List<Msg> msgs, ModelParams params) {
        return chat(msgs, List.of(), params);
    }

    Mono<ModelResponse> chat(List<Msg> msgs, List<ToolSpec> tools, ModelParams params);

    default Flux<ModelChunk> stream(List<Msg> msgs, ModelParams params) {
        return stream(msgs, List.of(), params);
    }

    Flux<ModelChunk> stream(List<Msg> msgs, List<ToolSpec> tools, ModelParams params);

    Mono<List<Float>> embed(String text);

    /**
     * 模型调用参数。
     * @param temperature   温度（可选）
     * @param maxTokens     输出最大 token 数（可选）
     * @param contextLength 模型上下文窗口大小（可选）。设置后 ReAct 策略按比例做预算管理，防止撑爆上下文。
     * @param extra         额外参数（prefix_caching 等）
     */
    record ModelParams(Double temperature, Integer maxTokens, Integer contextLength, Map<String, Object> extra) {
        public ModelParams {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        /** 默认参数。启用 prefix_caching 以降低首 Token 延迟与成本。 */
        public static ModelParams defaults() {
            return new ModelParams(null, null, null, Map.of("prefix_caching", true));
        }

        public ModelParams withTemperature(double t) {
            return new ModelParams(t, maxTokens, contextLength, extra);
        }

        public ModelParams withMaxTokens(int mt) {
            return new ModelParams(temperature, mt, contextLength, extra);
        }

        /** 设置模型上下文窗口大小（token 数）。ReAct 策略按 {@code contextLength * ratio} 做预算。 */
        public ModelParams withContextLength(int ctxLen) {
            return new ModelParams(temperature, maxTokens, ctxLen, extra);
        }

        /** 启用 Prompt 前缀缓存（DeepSeek 等支持的服务端缓存固定前缀，降低首 Token 延迟）。 */
        public ModelParams withPrefixCaching(boolean enabled) {
            var m = new HashMap<>(extra);
            m.put("prefix_caching", enabled);
            return new ModelParams(temperature, maxTokens, contextLength, m);
        }

        /** 添加额外参数（如 response_format 等），合并到已有 extra 中。 */
        public ModelParams withExtra(String key, Object value) {
            var m = new HashMap<>(extra);
            m.put(key, value);
            return new ModelParams(temperature, maxTokens, contextLength, m);
        }
    }

    record ModelResponse(Msg msg, TokenUsage usage) {
    }
}
