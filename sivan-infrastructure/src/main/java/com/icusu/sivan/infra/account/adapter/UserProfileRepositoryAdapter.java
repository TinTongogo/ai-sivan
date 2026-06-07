package com.icusu.sivan.infra.account.adapter;

import com.icusu.sivan.domain.account.IUserProfileRepository;
import com.icusu.sivan.domain.account.UserProfile;
import com.icusu.sivan.infra.account.entity.UserProfileEntity;
import com.icusu.sivan.infra.account.repository.UserProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户画像仓储适配器。
 */
@Component
@RequiredArgsConstructor
public class UserProfileRepositoryAdapter implements IUserProfileRepository {

    private final UserProfileJpaRepository jpaRepository;

    @Override
    public Optional<UserProfile> findByAccountId(UUID accountId) {
        return jpaRepository.findByAccountIdAndActiveTrue(accountId).map(this::toDomain);
    }

    @Override
    public UserProfile save(UserProfile profile) {
        var entity = toEntity(profile);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void delete(UUID profileId) {
        jpaRepository.deleteById(profileId);
    }

    private UserProfile toDomain(UserProfileEntity entity) {
        return UserProfile.builder()
                .profileId(entity.getProfileId())
                .accountId(entity.getAccountId())
                .name(entity.getName())
                .bio(entity.getBio())
                .aiLanguage(entity.getAiLanguage())
                .expertise(entity.getExpertise() != null ? entity.getExpertise() : java.util.Collections.emptyList())
                .active(entity.isActive())
                .vector(entity.getVector())
                .autoLearn(entity.isAutoLearn())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    private UserProfileEntity toEntity(UserProfile domain) {
        var entity = new UserProfileEntity();
        entity.setProfileId(domain.getProfileId());
        entity.setAccountId(domain.getAccountId());
        entity.setName(domain.getName());
        entity.setBio(domain.getBio());
        entity.setAiLanguage(domain.getAiLanguage() != null ? domain.getAiLanguage() : "auto");
        entity.setExpertise(domain.getExpertise() != null ? domain.getExpertise() : java.util.Collections.emptyList());
        entity.setActive(domain.isActive());
        if (domain.getVector() != null) {
            entity.setVector(domain.getVector());
        }
        entity.setAutoLearn(domain.isAutoLearn());
        return entity;
    }
}
