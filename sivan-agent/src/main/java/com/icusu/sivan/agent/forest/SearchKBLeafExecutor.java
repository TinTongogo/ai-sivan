package com.icusu.sivan.agent.forest;

import com.icusu.sivan.domain.forest.ForestEvent;
import com.icusu.sivan.domain.forest.context.ExecutionContext;
import com.icusu.sivan.domain.shared.port.EventSink;
import com.icusu.sivan.domain.forest.port.LeafExecutor;
import com.icusu.sivan.domain.forest.tree.ContentNode;
import com.icusu.sivan.domain.forest.tree.TreeNode;
import com.icusu.sivan.domain.knowledge.KbSearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 知识库搜索叶子执行器 — 处理 kb_search 节点类型（10-知识库与RAG §4.5）。
 * <p>
 * 在 GoalTree 执行中通过 {@link KbSearchPort} 调用语义搜索，
 * 搜索结果格式化为上下文注入执行流。
 */
@Component
public class SearchKBLeafExecutor implements LeafExecutor {

    private static final Logger log = LoggerFactory.getLogger(SearchKBLeafExecutor.class);

    private final KbSearchPort kbSearchPort;

    public SearchKBLeafExecutor(KbSearchPort kbSearchPort) {
        this.kbSearchPort = kbSearchPort;
    }

    @Override
    public String supportedType() {
        return "kb_search";
    }

    @Override
    public Flux<ForestEvent> execute(TreeNode node, ExecutionContext ctx, EventSink sink) {
        String query = node instanceof ContentNode cn ? cn.content() : "";
        if (query.isBlank()) {
            return Flux.just(ForestEvent.detail(node.nodeId(), null,
                    ctx.accountId().toString(), ""));
        }

        // 从 metadata 获取 kbName 和 topK
        String kbName = null;
        int topK = 5;
        if (node instanceof ContentNode cn) {
            Object rawKb = cn.metadata().get("kbName");
            if (rawKb instanceof String s && !s.isBlank()) kbName = s;
            Object rawTopK = cn.metadata().get("topK");
            if (rawTopK instanceof String s) {
                try { topK = Integer.parseInt(s); } catch (Exception ignored) {}
            }
        }

        String finalKbName = kbName;
        int finalTopK = topK;
        log.info("[SearchKB] 执行知识库搜索: query={} kbName={} topK={}", query, finalKbName, finalTopK);

        return Mono.fromCallable(() -> {
            List<String> results = kbSearchPort.search(query, finalKbName, finalTopK, ctx.accountId());

            StringBuilder context = new StringBuilder();
            context.append("## 知识库搜索结果\n");
            context.append("查询: ").append(query).append("\n");
            if (results != null && !results.isEmpty()) {
                for (String r : results) {
                    // 格式: "score||content"
                    int sep = r.indexOf("||");
                    if (sep > 0) {
                        String score = r.substring(0, sep);
                        String text = r.substring(sep + 2);
                        context.append("- [").append(score).append("] ").append(text).append("\n");
                    } else {
                        context.append("- ").append(r).append("\n");
                    }
                }
            } else {
                context.append("（无结果）\n");
            }
            return context.toString();
        })
        .flatMapMany(context -> Flux.just(
                ForestEvent.detail(node.nodeId(), null, ctx.accountId().toString(), context),
                ForestEvent.detail(node.nodeId(), null, ctx.accountId().toString(),
                        "{\"type\":\"kb_search\",\"query\":\"" + query
                        + "\",\"resultCount\":" + (context.length() > 50 ? 1 : 0) + "}")
        ))
        .onErrorResume(e -> {
            log.error("[SearchKB] 搜索失败: {}", e.getMessage());
            return Flux.just(ForestEvent.error(node.nodeId(), null,
                    ctx.accountId().toString(), "知识库搜索失败: " + e.getMessage()));
        });
    }

    @Override
    public int maxRetries() {
        return 1;
    }
}
