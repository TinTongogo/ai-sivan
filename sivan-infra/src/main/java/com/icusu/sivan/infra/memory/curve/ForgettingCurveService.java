package com.icusu.sivan.infra.memory.curve;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.forest.port.ForestRepository;
import com.icusu.sivan.domain.forest.tree.node.MemoryNode;
import com.icusu.sivan.domain.memory.curve.EbbinghausForgettingCurve;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 遗忘曲线调度服务。
 * 定时扫描 forest_nodes 中 type='memory' 的节点，重新计算保留率，更新列和 metadata。
 * <p>
 * 自动晋升路径：SESSION → PROJECT（>5次访问且保留率>0.5）→ USER（>15次访问且保留率>0.7）。
 */
@Slf4j
@Service
public class ForgettingCurveService {

    private final ForestRepository forestRepository;

    public ForgettingCurveService(ForestRepository forestRepository) {
        this.forestRepository = forestRepository;
    }

    @Scheduled(fixedRate = 3600000)
    public void autoArchive() {
        try {
            long total = forestRepository.countActiveMemories(null);
            if (total == 0) return;
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int archived = 0;
            int promotedToProject = 0;
            int promotedToUser = 0;

            // 定时任务，扫描所有 memory 节点
            var allNodes = forestRepository.findNodesByTypeAndAccount(null, "memory", (int) total);
            for (var node : allNodes) {
                if (!(node instanceof MemoryNode mn)) continue;
                var meta = mn.metadata();
                String levelStr = (String) meta.get("level");
                if (levelStr == null) continue;
                MemoryLevel level = MemoryLevel.valueOf(levelStr);
                Integer accessCount = (Integer) meta.getOrDefault("accessCount", 0);
                String lastAccessedStr = (String) meta.get("lastAccessedAt");
                if (lastAccessedStr == null) continue;
                LocalDateTime lastAccessed = LocalDateTime.parse(lastAccessedStr);
                boolean important = Boolean.TRUE.equals(meta.get("important"));

                if (important) continue;

                double retention = EbbinghausForgettingCurve.calculateRetentionWithAccess(
                        level, lastAccessed, Math.max(accessCount, 1), now);

                if (retention < EbbinghausForgettingCurve.ARCHIVE_THRESHOLD) {
                    meta.put("archived", true);
                    forestRepository.updateNodeContent(mn.nodeId(), mn.content(), meta, null);
                    archived++;
                    continue;
                }

                // SESSION → PROJECT
                if (level == MemoryLevel.SESSION && accessCount > 5 && retention > 0.5) {
                    meta.put("level", MemoryLevel.PROJECT.name());
                    forestRepository.updateNodeContent(mn.nodeId(), mn.content(), meta, null);
                    promotedToProject++;
                    forestRepository.updateMemoryRetention(mn.nodeId(), retention, null);
                    continue;
                }

                // PROJECT → USER
                if (level == MemoryLevel.PROJECT && accessCount > 15 && retention > 0.7) {
                    meta.put("level", MemoryLevel.USER.name());
                    forestRepository.updateNodeContent(mn.nodeId(), mn.content(), meta, null);
                    promotedToUser++;
                    forestRepository.updateMemoryRetention(mn.nodeId(), retention, null);
                }
            }

            if (archived > 0) log.info("遗忘曲线归档: {} 条已归档", archived);
            if (promotedToProject > 0) log.info("记忆晋升 SESSION → PROJECT: {} 条", promotedToProject);
            if (promotedToUser > 0) log.info("记忆晋升 PROJECT → USER: {} 条", promotedToUser);
        } catch (Exception e) {
            log.warn("自动归档异常", e);
        }
    }
}
