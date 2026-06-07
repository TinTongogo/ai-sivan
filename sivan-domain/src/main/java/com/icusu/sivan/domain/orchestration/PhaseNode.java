package com.icusu.sivan.domain.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.icusu.sivan.common.enums.SquadMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Squad 阶段节点实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhaseNode {

    private Integer phase;
    private String name;
    private SquadMode mode;
    private List<String> agents;
    private String description;
    private String inputFilter;
    private String outputFilter;

    /** HITL 介入模式：NONE / PRE / POST / ALL / AGENT_LIST。null 等价 NONE。 */
    private String hitlMode;
    /** AGENT_LIST 模式下需要审核的 Agent 名称列表。 */
    private List<String> hitlAgents;

    /** 调度层：显式依赖声明。null → 由 SquadMode 隐式推断（向后兼容）。 */
    private List<Integer> dependsOn;

    /** 协同层：结构化条件路由（替代 CONDITIONAL 模式 LLM 截断路由）。 */
    private List<PhaseCondition> conditions;
}
