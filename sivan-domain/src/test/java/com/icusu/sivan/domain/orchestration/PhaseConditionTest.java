package com.icusu.sivan.domain.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhaseConditionTest {

    @Test
    void shouldCreateSingleCondition() {
        var cond = new SingleCondition("confidence", ">", "0.7");
        assertEquals("confidence", cond.sourceField());
        assertEquals(">", cond.operator());
        assertEquals("0.7", cond.value());
    }

    @Test
    void nullGroupShouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new PhaseCondition(1, null));
    }

    @Test
    void shouldCreateConditionWithTargetPhase() {
        var group = new ConditionGroup(
                List.of(new SingleCondition("content", "contains", "API")),
                LogicOp.AND);
        var phaseCond = new PhaseCondition(3, group);
        assertEquals(3, phaseCond.targetPhase());
        assertEquals(LogicOp.AND, phaseCond.group().op());
        assertEquals(1, phaseCond.group().conditions().size());
    }

    @Test
    void shouldSupportMultipleConditions() {
        var group = new ConditionGroup(
                List.of(
                        new SingleCondition("confidence", ">", "0.7"),
                        new SingleCondition("summary", "contains", "完成")
                ),
                LogicOp.AND);
        assertEquals(2, group.conditions().size());
    }

    @Test
    void shouldSupportOrLogic() {
        var group = new ConditionGroup(
                List.of(
                        new SingleCondition("confidence", ">", "0.7"),
                        new SingleCondition("confidence", "<", "0.3")
                ),
                LogicOp.OR);
        assertEquals(LogicOp.OR, group.op());
    }

    @Test
    void emptyConditionsShouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                new ConditionGroup(List.of(), LogicOp.AND));
    }
}
