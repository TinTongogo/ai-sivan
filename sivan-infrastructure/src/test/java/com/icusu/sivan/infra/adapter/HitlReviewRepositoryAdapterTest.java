package com.icusu.sivan.infra.orchestration.adapter;

import com.icusu.sivan.domain.orchestration.HitlReview;
import com.icusu.sivan.domain.orchestration.IHitlReviewRepository;
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
class HitlReviewRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IHitlReviewRepository repository;

    @Test
    void shouldSaveAndFindById() {
        UUID accountId = UUID.randomUUID();
        HitlReview review = HitlReview.builder()
                .accountId(accountId)
                .executionId(UUID.randomUUID())
                .phase(0)
                .phaseName("审核阶段")
                .inputContent("输入内容")
                .status("PENDING")
                .build();
        repository.save(review);
        assertNotNull(review.getReviewId());

        HitlReview found = repository.findById(review.getReviewId()).orElse(null);
        assertNotNull(found);
        assertEquals("审核阶段", found.getPhaseName());
        assertEquals("PENDING", found.getStatus());
    }

    @Test
    void shouldFindByExecutionId() {
        UUID execId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        repository.save(HitlReview.builder().accountId(accountId).executionId(execId).phase(0)
                .phaseName("阶段0").inputContent("输入").status("PENDING").build());
        repository.save(HitlReview.builder().accountId(accountId).executionId(execId).phase(1)
                .phaseName("阶段1").inputContent("输入").status("PENDING").build());

        List<HitlReview> reviews = repository.findByExecutionId(execId);
        assertEquals(2, reviews.size());
    }

    @Test
    void shouldDeleteByExecutionId() {
        UUID execId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        repository.save(HitlReview.builder().accountId(accountId).executionId(execId).phase(0)
                .phaseName("待删除").inputContent("输入").status("PENDING").build());

        repository.deleteByExecutionId(execId);
        assertTrue(repository.findByExecutionId(execId).isEmpty());
    }


}
