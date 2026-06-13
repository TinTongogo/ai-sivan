package com.icusu.sivan.infra.routing;

import com.icusu.sivan.domain.routing.IBetaParamRepository;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import com.icusu.sivan.domain.task.TaskFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 路由反馈处理器 — 节点完成后更新 Beta 参数和 embedding。
 * <p>
 * 由 ForestExecutor 在节点执行完毕后直接调用。
 */
@Component
public class RouteFeedbackHandler {

    private static final Logger log = LoggerFactory.getLogger(RouteFeedbackHandler.class);

    private final IBetaParamRepository betaRepo;
    private final IEmbeddingService embeddingService;
    private final JdbcTemplate jdbc;

    public RouteFeedbackHandler(IBetaParamRepository betaRepo, IEmbeddingService embeddingService,
                                JdbcTemplate jdbc) {
        this.betaRepo = betaRepo;
        this.embeddingService = embeddingService;
        this.jdbc = jdbc;
    }

    /**
     * 节点执行完毕后记录反馈。
     *
     * @param accountId  账户 ID
     * @param agentName  使用的 Agent 名称
     * @param taskContent 节点任务内容
     * @param success    是否成功
     */
    public void onNodeCompleted(UUID accountId, String agentName, String taskContent, boolean success) {
        if (accountId == null || agentName == null || taskContent == null) return;

        try {
            // 1. 计算 embedding（非阻塞，失败静默）
            float[] emb = null;
            try {
                emb = embeddingService.embed(taskContent);
            } catch (Exception e) {
                log.warn("[反馈] embedding 计算失败(跳过写入): {}", e.getMessage());
            }

            // 2. 写入 routing_decisions
            if (emb != null) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < emb.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(emb[i]);
                }
                sb.append("]");
                jdbc.update(
                        "INSERT INTO routing_decisions (account_id, task_description, selected_agent, success, task_embedding, strategy) " +
                        "VALUES (?, ?, ?, ?, ?::vector, 'pg_route')",
                        accountId, truncate(taskContent, 500), agentName, success, sb.toString());
            } else {
                jdbc.update(
                        "INSERT INTO routing_decisions (account_id, task_description, selected_agent, success, strategy) " +
                        "VALUES (?, ?, ?, ?, 'pg_route')",
                        accountId, truncate(taskContent, 500), agentName, success);
            }

            // 3. 更新 Beta 参数（使用结构化特征哈希，提升泛化能力）
            String hash = md5(TaskFeatures.fromContent(taskContent).toString());
            betaRepo.upsert(accountId, hash, agentName, success);

            log.debug("[反馈] agent={} success={}", agentName, success);
        } catch (Exception e) {
            log.warn("[反馈] 异常(不影响主流程): {}", e.getMessage());
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
