package com.icusu.sivan.domain.orchestration;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.common.enums.SquadSource;

import java.util.List;
import java.util.UUID;

/**
 * Squad 领域工厂。封装 Squad 创建逻辑，强制不变量校验。
 */
public final class SquadFactory {

    private SquadFactory() {}

    /** 创建用户手动定义的 Squad。 */
    public static Squad createUserSquad(UUID accountId, UUID projectId, String name,
                                         String description, SquadMode mode, List<PhaseNode> phases) {
        return Squad.builder()
                .accountId(accountId)
                .projectId(projectId)
                .name(name)
                .description(description)
                .mode(mode != null ? mode : SquadMode.SEQUENTIAL)
                .source(SquadSource.USER)
                .active(true)
                .phases(phases != null ? phases : List.of())
                .usageCount(0)
                .successRate(0.0)
                .build();
    }

    /** 创建系统自动生成的 Squad。 */
    public static Squad createSystemSquad(UUID accountId, String name, String description,
                                           SquadMode mode, List<PhaseNode> phases) {
        return createSystemSquad(accountId, name, description, mode, phases, null);
    }

    /** 创建系统自动生成的 Squad，指定来源模板 ID。 */
    public static Squad createSystemSquad(UUID accountId, String name, String description,
                                           SquadMode mode, List<PhaseNode> phases,
                                           UUID sourcePatternId) {
        return Squad.builder()
                .accountId(accountId)
                .name(name)
                .description(description)
                .mode(mode != null ? mode : SquadMode.SEQUENTIAL)
                .source(SquadSource.SYSTEM)
                .active(true)
                .phases(phases != null ? phases : List.of())
                .usageCount(0)
                .successRate(0.0)
                .sourcePatternId(sourcePatternId)
                .build();
    }
}
