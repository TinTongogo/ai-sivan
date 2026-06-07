package com.icusu.sivan.memory.curve;

import com.icusu.sivan.common.enums.MemoryLevel;
import com.icusu.sivan.domain.memory.MemoryEntry;
import com.icusu.sivan.domain.memory.IMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 遗忘曲线调度服务。
 * 定时扫描所有记忆条目，重新计算保留率，标记需归档的条目，输出建议复习列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForgettingCurveService {

    private final IMemoryRepository memoryRepository;

    /**
     * 扫描并归档已遗忘的记忆（基于遗忘曲线动态计算保留率）。
     * 同时晋升高频访问的 SESSION 层级记忆到 USER 层级。
     * 每小时执行，扫描所有账户的未归档记忆条目。
     */
    @Scheduled(fixedRate = 3600000) // 每小时
    public void autoArchive() {
        try {
            List<MemoryEntry> allMemories = memoryRepository.findAllNonArchived();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int archived = 0;
            int promoted = 0;

            for (MemoryEntry entry : allMemories) {
                if (Boolean.TRUE.equals(entry.getImportant())) continue;
                if (entry.getLevel() == null || entry.getLastAccessedAt() == null) continue;
                int access = entry.getAccessCount() != null ? entry.getAccessCount() : 0;
                double retention = EbbinghausForgettingCurve.calculateRetentionWithAccess(
                        entry.getLevel(), entry.getLastAccessedAt(), Math.max(access, 1), now);

                // 归档低保留率记忆
                if (retention < EbbinghausForgettingCurve.ARCHIVE_THRESHOLD) {
                    entry.setArchived(true);
                    memoryRepository.update(entry);
                    archived++;
                    continue;
                }

                // 晋升高频访问的 SESSION 级记忆到 USER 级
                if (entry.getLevel() == MemoryLevel.SESSION
                        && access > 5 && retention > 0.5) {
                    entry.setLevel(MemoryLevel.USER);
                    memoryRepository.update(entry);
                    promoted++;
                }
            }

            if (archived > 0) {
                log.info("遗忘曲线归档: {} 条已遗忘记忆被归档", archived);
            }
            if (promoted > 0) {
                log.info("记忆自动晋升: {} 条 SESSION 级记忆晋升为 USER 级", promoted);
            }
        } catch (Exception e) {
            log.warn("自动归档异常", e);
        }
    }

}
