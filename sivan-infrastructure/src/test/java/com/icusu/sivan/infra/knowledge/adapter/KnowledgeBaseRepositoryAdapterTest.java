package com.icusu.sivan.infra.knowledge.adapter;

import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.shared.vo.Chunk;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库仓储适配器集成测试。
 */
@Sql("/disable-fk.sql")
@Transactional
class KnowledgeBaseRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IKnowledgeBaseRepository kbRepository;

    /** 保存知识库后能按名称查询到。 */
    @Test
    void shouldSaveAndFindByName() {
        UUID accountId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .kbName("test-kb")
                .accountId(accountId)
                .description("测试知识库")
                .build();
        kbRepository.save(kb);

        KnowledgeBase found = kbRepository.findByNameAndAccount("test-kb", accountId).orElse(null);
        assertNotNull(found);
        assertEquals("test-kb", found.getKbName());
        assertEquals("测试知识库", found.getDescription());
    }

    /** 按账号列出所有知识库。 */
    @Test
    void shouldListAllByAccount() {
        UUID accountId = UUID.randomUUID();

        kbRepository.save(KnowledgeBase.builder().kbName("kb1").accountId(accountId).build());
        kbRepository.save(KnowledgeBase.builder().kbName("kb2").accountId(accountId).build());

        List<KnowledgeBase> list = kbRepository.findAllByAccount(accountId);
        assertEquals(2, list.size());
    }

    /** 按名称和账号删除知识库。 */
    @Test
    void shouldDeleteByNameAndAccount() {
        UUID accountId = UUID.randomUUID();
        kbRepository.save(KnowledgeBase.builder().kbName("delete-kb").accountId(accountId).build());

        kbRepository.deleteByNameAndAccount("delete-kb", accountId);

        assertTrue(kbRepository.findByNameAndAccount("delete-kb", accountId).isEmpty());
    }

    /** 保存文档到知识库。 */
    @Test
    void shouldSaveDocument() {
        UUID accountId = UUID.randomUUID();
        kbRepository.save(KnowledgeBase.builder().kbName("doc-kb").accountId(accountId).build());

        KbDocument doc = KbDocument.builder()
                .kbName("doc-kb")
                .accountId(accountId)
                .filename("test.txt")
                .fileType("txt")
                .textContent("Hello World")
                .build();
        kbRepository.saveDocument(doc);

        assertNotNull(doc.getDocId());

        KbDocument found = kbRepository.findDocumentById(doc.getDocId()).orElse(null);
        assertNotNull(found);
        assertEquals("test.txt", found.getFilename());
    }

    /** 存储向量并执行向量搜索。 */
    @Test
    void shouldStoreAndSearchVectors() {
        UUID accountId = UUID.randomUUID();
        kbRepository.save(KnowledgeBase.builder().kbName("vec-kb").accountId(accountId).build());

        UUID docId = UUID.randomUUID();
        float[] vec1 = createTestVector(0.1f);
        float[] vec2 = createTestVector(0.2f);

        List<Chunk> chunks = List.of(
                Chunk.builder().chunkId(UUID.randomUUID().toString()).docId(docId)
                        .kbName("vec-kb").text("人工智能深度学习框架")
                        .contentType("text").build(),
                Chunk.builder().chunkId(UUID.randomUUID().toString()).docId(docId)
                        .kbName("vec-kb").text("机器学习的数学基础")
                        .contentType("text").build()
        );

        kbRepository.storeChunks("vec-kb", accountId, chunks, List.of(vec1, vec2));

        float[] queryVector = createTestVector(0.11f);
        List<SearchResult> results = kbRepository.search("vec-kb", accountId, queryVector, 5);

        assertFalse(results.isEmpty());
        assertEquals("vec-kb", results.get(0).getKbName());
    }

    /** 统计知识库中的 Chunk 数量。 */
    @Test
    void shouldCountChunks() {
        UUID accountId = UUID.randomUUID();
        kbRepository.save(KnowledgeBase.builder().kbName("count-kb").accountId(accountId).build());

        UUID docId = UUID.randomUUID();
        List<Chunk> chunks = List.of(
                Chunk.builder().chunkId(UUID.randomUUID().toString()).docId(docId)
                        .kbName("count-kb").text("chunk 1").contentType("text").build(),
                Chunk.builder().chunkId(UUID.randomUUID().toString()).docId(docId)
                        .kbName("count-kb").text("chunk 2").contentType("text").build()
        );
        List<float[]> vectors = List.of(createTestVector(0.1f), createTestVector(0.2f));

        kbRepository.storeChunks("count-kb", accountId, chunks, vectors);

        int count = kbRepository.countChunks("count-kb", accountId);
        assertEquals(2, count);
    }

    /** 生成 1024 维测试向量，所有元素设为同一值 */
    private float[] createTestVector(float value) {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, value);
        return vector;
    }
}
