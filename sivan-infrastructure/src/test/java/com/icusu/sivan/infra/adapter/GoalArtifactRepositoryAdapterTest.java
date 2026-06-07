package com.icusu.sivan.infra.goal.adapter;

import com.icusu.sivan.domain.goal.GoalArtifact;
import com.icusu.sivan.domain.goal.IGoalArtifactRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class GoalArtifactRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IGoalArtifactRepository repository;

    @Test
    void shouldSaveAndFindByGoalId() {
        UUID goalId = UUID.randomUUID();
        GoalArtifact artifact = GoalArtifact.builder()
                .goalId(goalId).milestoneOrder(0).taskOrder(1)
                .filePath("output/result.txt").fileType("doc").summary("结果文件")
                .fileSize(100L)
                .build();
        repository.save(artifact);
        assertNotNull(artifact.getArtifactId());

        List<GoalArtifact> artifacts = repository.findByGoalId(goalId);
        assertEquals(1, artifacts.size());
    }

    @Test
    void shouldSaveAll() {
        UUID goalId = UUID.randomUUID();
        List<GoalArtifact> artifacts = List.of(
                GoalArtifact.builder().goalId(goalId).milestoneOrder(0).taskOrder(1)
                        .filePath("output/a.txt").fileType("doc").summary("A").fileSize(10L).build(),
                GoalArtifact.builder().goalId(goalId).milestoneOrder(0).taskOrder(1)
                        .filePath("output/b.txt").fileType("doc").summary("B").fileSize(20L).build()
        );
        repository.saveAll(artifacts);

        List<GoalArtifact> found = repository.findByGoalId(goalId);
        assertEquals(2, found.size());
    }

    @Test
    void shouldDeleteByGoalId() {
        UUID goalId = UUID.randomUUID();
        repository.save(GoalArtifact.builder().goalId(goalId).milestoneOrder(0).taskOrder(1)
                .filePath("output/del.txt").fileType("doc").summary("待删除").fileSize(0L).build());

        repository.deleteByGoalId(goalId);
        assertTrue(repository.findByGoalId(goalId).isEmpty());
    }
}
