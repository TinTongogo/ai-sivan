package com.icusu.sivan.infra.account.adapter;

import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.infra.account.repository.AccountJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 账号仓储适配器，实现 IAccountRepository。
 */
@Component
@RequiredArgsConstructor
public class AccountRepositoryAdapter implements IAccountRepository {

    private final AccountJpaRepository jpaRepository;

    /**
     * 根据 ID 查询账号。
     */
    @Override
    public Optional<Account> findById(UUID accountId) {
        return jpaRepository.findById(accountId).map(this::toDomain);
    }

    /**
     * 根据用户名查询账号。
     */
    @Override
    public Optional<Account> findByUsername(String username) {
        return jpaRepository.findByUsername(username).map(this::toDomain);
    }

    /**
     * 根据邮箱查询账号。
     */
    @Override
    public Optional<Account> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(this::toDomain);
    }

    /**
     * 保存账号，回写 ID 和时间戳。
     */
    @Override
    public void save(Account account) {
        var entity = toEntity(account);
        jpaRepository.save(entity);
        if (account.getAccountId() == null) {
            account.setAccountId(entity.getAccountId());
        }
        account.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null);
        account.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null);
    }

    /**
     * 检查用户名是否已存在。
     */
    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    /**
     * 检查邮箱是否已存在。
     */
    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    /**
     * 根据短标识符查找账户。
     */
    @Override
    public Optional<Account> findByShortId(String shortId) {
        return jpaRepository.findByShortId(shortId).map(this::toDomain);
    }

    /**
     * 检查短标识符是否已存在（全局唯一）。
     */
    @Override
    public boolean existsByShortId(String shortId) {
        return jpaRepository.existsByShortId(shortId);
    }

    /**
     * 将实体转换为领域对象。
     */
    private Account toDomain(com.icusu.sivan.infra.account.entity.AccountEntity entity) {
        return Account.builder()
                .accountId(entity.getAccountId())
                .username(entity.getUsername())
                .email(entity.getEmail())
                .passwordHash(entity.getPasswordHash())
                .displayName(entity.getDisplayName())
                .preferences(entity.getPreferences())
                .quota(entity.getQuota())
                .shortId(entity.getShortId())
                .status(entity.getStatus())
                .tokenVersion(entity.getTokenVersion())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .build();
    }

    /**
     * 将领域对象转换为实体。
     */
    private com.icusu.sivan.infra.account.entity.AccountEntity toEntity(Account domain) {
        var entity = new com.icusu.sivan.infra.account.entity.AccountEntity();
        entity.setAccountId(domain.getAccountId());
        entity.setUsername(domain.getUsername());
        entity.setEmail(domain.getEmail());
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setDisplayName(domain.getDisplayName());
        entity.setPreferences(domain.getPreferences());
        entity.setQuota(domain.getQuota());
        entity.setShortId(domain.getShortId());
        entity.setStatus(domain.getStatus() != null ? domain.getStatus() : "active");
        entity.setTokenVersion(domain.getTokenVersion());
        return entity;
    }
}
