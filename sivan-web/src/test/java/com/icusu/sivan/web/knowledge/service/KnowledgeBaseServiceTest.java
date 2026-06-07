package com.icusu.sivan.web.knowledge.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.knowledge.KbDocument;
import com.icusu.sivan.domain.knowledge.KnowledgeBase;
import com.icusu.sivan.domain.knowledge.IKnowledgeBaseRepository;
import com.icusu.sivan.domain.shared.vo.SearchResult;
import com.icusu.sivan.infra.knowledge.EmbeddingService;
import com.icusu.sivan.web.file.service.FileStorageService;
import com.icusu.sivan.web.knowledge.dto.CreateKnowledgeBaseRequest;
import com.icusu.sivan.web.knowledge.dto.SearchRequest;
import com.icusu.sivan.web.knowledge.dto.UpdateKnowledgeBaseRequest;
import com.icusu.sivan.web.knowledge.dto.KbDocumentResponse;
import com.icusu.sivan.web.knowledge.dto.KnowledgeBaseResponse;
import com.icusu.sivan.web.knowledge.dto.SearchResultResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** 知识库服务测试。 */
class KnowledgeBaseServiceTest {

    @Mock
    private IKnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private com.icusu.sivan.infra.knowledge.RerankerService rerankerService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private QueryRewriter queryRewriter;
    @Mock
    private SemanticChunker semanticChunker;

    private KnowledgeBaseService knowledgeBaseService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    /** 初始化测试环境。 */
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseService(knowledgeBaseRepository, embeddingService, rerankerService, fileStorageService, queryRewriter, semanticChunker);
    }

    @Test
    /** 创建知识库成功。 */
    void create_shouldSucceed() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setKbName("test-kb");
        request.setDescription("测试知识库");

        when(knowledgeBaseRepository.findByNameAndAccount("test-kb", accountId)).thenReturn(Optional.empty());

        KnowledgeBaseResponse response = knowledgeBaseService.create(accountId, request);

        assertEquals("test-kb", response.getKbName());
        assertEquals("测试知识库", response.getDescription());
        verify(knowledgeBaseRepository).save(any(KnowledgeBase.class));
    }

    @Test
    /** 创建重名知识库应抛出异常。 */
    void create_shouldThrowWhenNameExists() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setKbName("duplicate");

        when(knowledgeBaseRepository.findByNameAndAccount("duplicate", accountId))
                .thenReturn(Optional.of(new KnowledgeBase()));

        assertThrows(DomainException.class, () -> knowledgeBaseService.create(accountId, request));
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    /** 根据名称获取知识库。 */
    void getByName_shouldReturnKb() {
        KnowledgeBase kb = KnowledgeBase.builder()
                .kbName("my-kb").accountId(accountId).description("desc").build();

        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(kb));

        KnowledgeBaseResponse response = knowledgeBaseService.getByName(accountId, "my-kb");

        assertEquals("my-kb", response.getKbName());
        assertEquals("desc", response.getDescription());
    }

    @Test
    /** 获取不存在的知识库应抛出异常。 */
    void getByName_shouldThrowWhenNotFound() {
        when(knowledgeBaseRepository.findByNameAndAccount("nonexistent", accountId))
                .thenReturn(Optional.empty());

        assertThrows(DomainException.class,
                () -> knowledgeBaseService.getByName(accountId, "nonexistent"));
    }

    @Test
    /** 列出所有知识库。 */
    void list_shouldReturnAll() {
        KnowledgeBase kb = KnowledgeBase.builder().kbName("kb1").accountId(accountId).build();
        when(knowledgeBaseRepository.findAllByAccount(accountId)).thenReturn(List.of(kb));

        List<KnowledgeBaseResponse> list = knowledgeBaseService.list(accountId);

        assertEquals(1, list.size());
        assertEquals("kb1", list.get(0).getKbName());
    }

    @Test
    /** 更新知识库描述。 */
    void update_shouldModifyDescription() {
        KnowledgeBase kb = KnowledgeBase.builder()
                .kbName("my-kb").accountId(accountId).description("旧描述").build();

        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(kb));

        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setDescription("新描述");

        KnowledgeBaseResponse response = knowledgeBaseService.update(accountId, "my-kb", request);

        assertEquals("新描述", response.getDescription());
        verify(knowledgeBaseRepository).save(kb);
    }

    @Test
    /** 删除知识库。 */
    void delete_shouldRemoveKb() {
        KnowledgeBase kb = KnowledgeBase.builder().kbName("my-kb").accountId(accountId).build();

        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(kb));

        knowledgeBaseService.delete(accountId, "my-kb");

        verify(knowledgeBaseRepository).deleteByNameAndAccount("my-kb", accountId);
    }

    @Test
    /** 列出知识库文档。 */
    void listDocuments_shouldReturnDocs() {
        KbDocument doc = KbDocument.builder()
                .docId(UUID.randomUUID()).kbName("my-kb").accountId(accountId)
                .filename("test.txt").fileType("txt").textContent("hello").build();

        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(KnowledgeBase.builder().kbName("my-kb").accountId(accountId).build()));
        when(knowledgeBaseRepository.findDocumentsByKb("my-kb", accountId))
                .thenReturn(List.of(doc));

        List<KbDocumentResponse> docs = knowledgeBaseService.listDocuments(accountId, "my-kb");

        assertEquals(1, docs.size());
        assertEquals("test.txt", docs.get(0).getFilename());
    }

    @Test
    /** 获取知识库文档详情。 */
    void getDocument_shouldReturnDoc() {
        UUID docId = UUID.randomUUID();
        KbDocument doc = KbDocument.builder()
                .docId(docId).kbName("my-kb").filename("doc.txt").build();

        when(knowledgeBaseRepository.findDocumentById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(KnowledgeBase.builder().kbName("my-kb").accountId(accountId).build()));

        KbDocumentResponse response = knowledgeBaseService.getDocument(docId, accountId);

        assertEquals("doc.txt", response.getFilename());
    }

    @Test
    /** 删除知识库文档。 */
    void deleteDocument_shouldRemove() {
        UUID docId = UUID.randomUUID();
        KbDocument doc = KbDocument.builder()
                .docId(docId).kbName("my-kb").accountId(accountId).build();
        when(knowledgeBaseRepository.findDocumentById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(KnowledgeBase.builder().kbName("my-kb").accountId(accountId).build()));

        knowledgeBaseService.deleteDocument(docId, accountId);
        verify(knowledgeBaseRepository).deleteDocument(docId);
    }

    @Test
    /** 向量搜索知识库。 */
    void search_shouldReturnVectorResults() {
        String query = "深度学习";
        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f, 0.4f};

        when(knowledgeBaseRepository.findByNameAndAccount("my-kb", accountId))
                .thenReturn(Optional.of(KnowledgeBase.builder().kbName("my-kb").accountId(accountId).build()));
        when(embeddingService.embed(query)).thenReturn(queryVector);
        when(knowledgeBaseRepository.search(eq("my-kb"), eq(accountId), eq(queryVector), anyInt()))
                .thenReturn(List.of(
                        SearchResult.builder()
                                .chunkId(UUID.randomUUID().toString())
                                .kbName("my-kb").text("深度学习教程")
                                .score(0.95).contentType("text").build()
                ));

        SearchRequest request = new SearchRequest();
        request.setQuery(query);

        List<SearchResultResponse> results = knowledgeBaseService.search(accountId, "my-kb", request);

        assertEquals(1, results.size());
        assertEquals("深度学习教程", results.get(0).getText());
        assertEquals(0.95, results.get(0).getScore());
        verify(embeddingService).embed(query);
        verify(knowledgeBaseRepository).search(eq("my-kb"), eq(accountId), eq(queryVector), anyInt());
    }
}
