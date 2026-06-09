package com.icusu.sivan.infra.forest.execution;

import com.icusu.sivan.common.NodeStatus;
import com.icusu.sivan.common.event.NodeStatusChanged;
import com.icusu.sivan.domain.forest.service.ForestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 监听 {@link NodeStatusChanged} 事件并将节点状态持久化到数据库。
 * <p>
 * {@link ForestExecutor} 在执行过程中发布状态变更事件，
 * 本监听器异步（或同步）将最新状态写入 {@link ForestRepository#updateNodeStatus}。
 */
@Component
public class NodeStatusPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(NodeStatusPersistenceListener.class);

    private final ForestRepository forestRepository;

    public NodeStatusPersistenceListener(ForestRepository forestRepository) {
        this.forestRepository = forestRepository;
    }

    /**
     * 同步持久化节点状态变更。
     * <p>
     * 在 {@code @Transactional} 上下文中，此方法会在同一事务中执行；
     * 在响应式流水线中，它运行在事件发布线程上。
     */
    @EventListener
    public void onNodeStatusChanged(NodeStatusChanged event) {
        try {
            NodeStatus newStatus = event.newStatus();
            UUID accountId = UUID.fromString(event.accountId());
            forestRepository.updateNodeStatus(event.nodeId(), newStatus, accountId);
            log.debug("[持久化] 节点状态已更新: nodeId={} status={}", event.nodeId(), newStatus);
        } catch (Exception e) {
            log.warn("[持久化] 节点状态更新失败: nodeId={} error={}", event.nodeId(), e.getMessage());
        }
    }
}
