package com.icusu.sivan.web.knowledge.service;

import com.icusu.sivan.agent.model.ModelRouter;
import com.icusu.sivan.core.message.Msg;
import com.icusu.sivan.core.message.Role;
import com.icusu.sivan.core.model.Model;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 知识库查询改写器。
 * <p>
 * 将用户原始查询扩展为 2-3 个语义变体，提高向量搜索的召回率。
 * LLM 调用失败时退回到原始单查询，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private final ModelRouter modelRouter;

    /** 查询改写系统提示词。 */
    private static final String REWRITE_PROMPT = """
            你是一个搜索查询改写专家。请将用户的搜索问题改写为 2-3 个不同表述的变体，
            覆盖同义词、不同表述角度、更精确的术语，以帮助搜索引擎找到更多相关结果。

            要求：
            - 每行一个变体，不要编号
            - 保持原意的同时使用不同的词汇和表述方式
            - 如果原问题很短，补充上下文相关的扩展
            - 直接输出变体，不要解释

            用户问题：%s""";

    /**
     * 改写查询，返回包括原始查询在内的 1-3 个变体。
     * 失败时返回仅包含原始查询的列表。
     */
    public Mono<List<String>> rewrite(String originalQuery, UUID accountId) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return Mono.just(java.util.Collections.singletonList(originalQuery));
        }

        String prompt = String.format(REWRITE_PROMPT, originalQuery);
        List<Msg> msgs = List.of(Msg.of(Role.USER, prompt));

        return modelRouter.getDefaultModel(accountId).chat(msgs, Model.ModelParams.defaults())
                .map(response -> {
                    String text = response.msg().text();
                    if (text == null || text.isBlank()) {
                        return List.of(originalQuery);
                    }
                    // 解析变体：每行一个
                    List<String> variants = text.lines()
                            .map(String::strip)
                            .filter(l -> !l.isBlank())
                            .map(l -> l.replaceAll("^\\d+[.、]\\s*", ""))  // 去除 "1." "2、" 前缀
                            .map(l -> l.replaceAll("^-\\s*", ""))          // 去除 "- " 前缀
                            .filter(l -> l.length() > 2)
                            .distinct()
                            .toList();
                    if (variants.isEmpty()) {
                        return List.of(originalQuery);
                    }
                    // 如果变体不含原始查询，加在开头
                    boolean hasOriginal = variants.stream().anyMatch(v -> v.contains(originalQuery));
                    if (!hasOriginal) {
                        var result = new java.util.ArrayList<String>();
                        result.add(originalQuery);
                        result.addAll(variants);
                        return List.copyOf(result);
                    }
                    return variants;
                })
                .onErrorResume(e -> {
                    log.warn("查询改写失败，退回到原始查询: {}", e.getMessage());
                    return Mono.just(List.of(originalQuery));
                });
    }
}
