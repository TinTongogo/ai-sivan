package com.icusu.sivan.infra.execution.adapter;

import com.icusu.sivan.domain.orchestration.ArtifactType;
import com.icusu.sivan.domain.orchestration.ExecutionArtifact;
import com.icusu.sivan.domain.orchestration.IExecutionArtifactRepository;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行产物仓储适配器集成测试。
 */
@Sql("/disable-fk.sql")
@Transactional
class ExecutionArtifactRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IExecutionArtifactRepository repository;

    private final UUID executionId = UUID.randomUUID();

    /** 保存产物后能通过 executionId 查询到。 */
    @Test
    void shouldSaveAndFindByExecutionId() {
        ExecutionArtifact artifact = new ExecutionArtifact(executionId, 0, "计划", "分析步骤", ArtifactType.DOC);
        repository.save(artifact);

        List<ExecutionArtifact> found = repository.findByExecutionId(executionId);
        assertEquals(1, found.size());
        assertEquals("计划", found.get(0).getName());
        assertEquals("分析步骤", found.get(0).getContent());
        assertEquals(ArtifactType.DOC, found.get(0).getArtifactType());
        assertNotNull(found.get(0).getArtifactId());
        assertNotNull(found.get(0).getCreatedAt());
    }

    /** 按类型查询产物。 */
    @Test
    void shouldFindByExecutionIdAndType() {
        repository.save(new ExecutionArtifact(executionId, 0, "设计文档", "内容", ArtifactType.DOC));
        repository.save(new ExecutionArtifact(executionId, 1, "main.java", "code", ArtifactType.CODE));

        List<ExecutionArtifact> codeArtifacts = repository.findByExecutionIdAndType(executionId, ArtifactType.CODE);
        assertEquals(1, codeArtifacts.size());
        assertEquals("main.java", codeArtifacts.get(0).getName());
    }

    /** 产物按创建时间升序返回。 */
    @Test
    void artifactsShouldBeOrderedByCreatedAt() throws Exception {
        repository.save(new ExecutionArtifact(executionId, 0, "第一步", "a", ArtifactType.DOC));
        Thread.sleep(2); // 确保 createdAt 时间戳不同
        repository.save(new ExecutionArtifact(executionId, 1, "第二步", "b", ArtifactType.DOC));

        List<ExecutionArtifact> artifacts = repository.findByExecutionId(executionId);
        assertEquals(2, artifacts.size());
        assertEquals("第一步", artifacts.get(0).getName());
        assertEquals("第二步", artifacts.get(1).getName());
    }

    /** 删除执行 ID 下的所有产物。 */
    @Test
    void shouldDeleteByExecutionId() {
        repository.save(new ExecutionArtifact(executionId, 0, "a", "content", ArtifactType.DOC));
        repository.save(new ExecutionArtifact(executionId, 1, "b", "content", ArtifactType.CODE));

        repository.deleteByExecutionId(executionId);

        List<ExecutionArtifact> artifacts = repository.findByExecutionId(executionId);
        assertTrue(artifacts.isEmpty());
    }

    /** 不存在的 executionId 返回空列表。 */
    @Test
    void shouldReturnEmptyForUnknownExecutionId() {
        List<ExecutionArtifact> artifacts = repository.findByExecutionId(UUID.randomUUID());
        assertTrue(artifacts.isEmpty());
    }

    /** 不存在的类型返回空列表。 */
    @Test
    void shouldReturnEmptyForUnknownType() {
        repository.save(new ExecutionArtifact(executionId, 0, "doc", "content", ArtifactType.DOC));

        List<ExecutionArtifact> found = repository.findByExecutionIdAndType(executionId, ArtifactType.DATA);
        assertTrue(found.isEmpty());
    }
}
