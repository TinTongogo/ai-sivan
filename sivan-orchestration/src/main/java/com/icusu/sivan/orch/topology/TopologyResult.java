package com.icusu.sivan.orch.topology;

import com.icusu.sivan.common.enums.SquadMode;
import com.icusu.sivan.domain.orchestration.PhaseNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 拓扑生成结果。
 */
@Data
@Builder
@AllArgsConstructor
public class TopologyResult {
    private SquadMode mode;
    private List<PhaseNode> phases;
    private boolean fromPattern;
    /** 命中的本能模板 ID（从模板创建时为非空）。 */
    private UUID patternId;
}
