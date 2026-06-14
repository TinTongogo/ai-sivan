package com.icusu.sivan.infra.account.adapter;

import com.icusu.sivan.domain.account.IProfileChangeLogRepository;
import com.icusu.sivan.domain.account.ProfileChangeLog;
import com.icusu.sivan.infra.account.entity.ProfileChangeLogEntity;
import com.icusu.sivan.infra.account.repository.ProfileChangeLogJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 画像变更日志仓储适配器。
 */
@Component
@RequiredArgsConstructor
public class ProfileChangeLogRepositoryAdapter implements IProfileChangeLogRepository {

    private final ProfileChangeLogJpaRepository jpaRepository;

    @Override
    public void save(ProfileChangeLog log) {
        ProfileChangeLogEntity entity = toEntity(log);
        jpaRepository.save(entity);
        // 回写 DB 生成的主键和时间戳
        log.setLogId(entity.getLogId());
        if (entity.getCreatedAt() != null) {
            log.setCreatedAt(entity.getCreatedAt().toLocalDateTime());
        }
    }

    @Override
    public List<ProfileChangeLog> findByAccountId(UUID accountId, int limit) {
        return jpaRepository.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private ProfileChangeLog toDomain(ProfileChangeLogEntity entity) {
        return ProfileChangeLog.builder()
                .logId(entity.getLogId())
                .accountId(entity.getAccountId())
                .source(entity.getSource())
                .fieldName(entity.getFieldName())
                .oldValue(entity.getOldValue())
                .newValue(entity.getNewValue())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .build();
    }

    private ProfileChangeLogEntity toEntity(ProfileChangeLog domain) {
        return ProfileChangeLogEntity.builder()
                .accountId(domain.getAccountId())
                .source(domain.getSource())
                .fieldName(domain.getFieldName())
                .oldValue(domain.getOldValue())
                .newValue(domain.getNewValue())
                .build();
    }
}
