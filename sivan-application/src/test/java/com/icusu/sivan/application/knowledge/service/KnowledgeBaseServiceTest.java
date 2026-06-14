package com.icusu.sivan.application.knowledge.service;

import com.icusu.sivan.application.knowledge.KbCrudService;
import com.icusu.sivan.application.knowledge.KbSearchService;
import com.icusu.sivan.application.knowledge.KnowledgeBaseService;
import com.icusu.sivan.application.knowledge.dto.*;
import com.icusu.sivan.common.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseService 委托层测试。
 * KnowledgeBaseService 是 KbCrudService + KbSearchService 的委托层，测试验证委托正确性。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KbCrudService kbCrudService;
    @Mock
    private KbSearchService kbSearchService;

    private KnowledgeBaseService knowledgeBaseService;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseService(kbCrudService, kbSearchService);
    }

    @Test
    void create_shouldDelegateToKbCrudService() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setKbName("test-kb");
        request.setDescription("测试知识库");
        KnowledgeBaseResponse expected = KnowledgeBaseResponse.builder().kbName("test-kb").build();
        when(kbCrudService.create(accountId, request)).thenReturn(expected);

        KnowledgeBaseResponse response = knowledgeBaseService.create(accountId, request);

        assertEquals("test-kb", response.getKbName());
        verify(kbCrudService).create(accountId, request);
    }

    @Test
    void create_shouldPropagateException() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setKbName("duplicate");
        when(kbCrudService.create(accountId, request)).thenThrow(DomainException.conflict(""));

        assertThrows(DomainException.class, () -> knowledgeBaseService.create(accountId, request));
    }

    @Test
    void getByName_shouldDelegate() {
        KnowledgeBaseResponse expected = KnowledgeBaseResponse.builder().kbName("my-kb").description("desc").build();
        when(kbCrudService.getByName(accountId, "my-kb")).thenReturn(expected);

        KnowledgeBaseResponse response = knowledgeBaseService.getByName(accountId, "my-kb");

        assertEquals("my-kb", response.getKbName());
        assertEquals("desc", response.getDescription());
    }

    @Test
    void getByName_shouldPropagateNotFound() {
        when(kbCrudService.getByName(accountId, "nonexistent")).thenThrow(DomainException.class);

        assertThrows(DomainException.class, () -> knowledgeBaseService.getByName(accountId, "nonexistent"));
    }

    @Test
    void list_shouldDelegate() {
        KnowledgeBaseResponse kb = KnowledgeBaseResponse.builder().kbName("kb1").build();
        when(kbCrudService.list(accountId)).thenReturn(List.of(kb));

        List<KnowledgeBaseResponse> list = knowledgeBaseService.list(accountId);

        assertEquals(1, list.size());
        assertEquals("kb1", list.get(0).getKbName());
    }

    @Test
    void update_shouldDelegate() {
        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setDescription("新描述");
        KnowledgeBaseResponse expected = KnowledgeBaseResponse.builder().description("新描述").build();
        when(kbCrudService.update(accountId, "my-kb", request)).thenReturn(expected);

        KnowledgeBaseResponse response = knowledgeBaseService.update(accountId, "my-kb", request);

        assertEquals("新描述", response.getDescription());
    }

    @Test
    void delete_shouldDelegate() {
        knowledgeBaseService.delete(accountId, "my-kb");

        verify(kbCrudService).delete(accountId, "my-kb");
    }

    @Test
    void listDocuments_shouldDelegate() {
        KbDocumentResponse doc = KbDocumentResponse.builder().filename("test.txt").build();
        when(kbCrudService.listDocuments(accountId, "my-kb")).thenReturn(List.of(doc));

        List<KbDocumentResponse> docs = knowledgeBaseService.listDocuments(accountId, "my-kb");

        assertEquals(1, docs.size());
        assertEquals("test.txt", docs.get(0).getFilename());
    }

    @Test
    void getDocument_shouldDelegate() {
        UUID docId = UUID.randomUUID();
        KbDocumentResponse expected = KbDocumentResponse.builder().filename("doc.txt").build();
        when(kbCrudService.getDocument(docId, accountId)).thenReturn(expected);

        KbDocumentResponse response = knowledgeBaseService.getDocument(docId, accountId);

        assertEquals("doc.txt", response.getFilename());
    }

    @Test
    void deleteDocument_shouldDelegate() {
        UUID docId = UUID.randomUUID();

        knowledgeBaseService.deleteDocument(docId, accountId);

        verify(kbCrudService).deleteDocument(docId, accountId);
    }

    @Test
    void search_shouldDelegate() {
        SearchRequest request = new SearchRequest();
        request.setQuery("深度学习");

        SearchResultResponse result = SearchResultResponse.builder().text("深度学习教程").score(0.95).build();
        when(kbSearchService.search(accountId, "my-kb", request)).thenReturn(List.of(result));

        List<SearchResultResponse> results = knowledgeBaseService.search(accountId, "my-kb", request);

        assertEquals(1, results.size());
        assertEquals("深度学习教程", results.get(0).getText());
        assertEquals(0.95, results.get(0).getScore());
    }
}
