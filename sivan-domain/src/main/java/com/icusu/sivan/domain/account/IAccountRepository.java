package com.icusu.sivan.domain.account;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户账户仓储接口。
 */
public interface IAccountRepository {

    /** 根据 ID 查找账户。 */
    Optional<Account> findById(UUID accountId);

    /** 根据用户名查找账户。 */
    Optional<Account> findByUsername(String username);

    /** 根据邮箱查找账户。 */
    Optional<Account> findByEmail(String email);

    /** 保存账户。 */
    void save(Account account);

    /** 检查用户名是否已存在。 */
    boolean existsByUsername(String username);

    /** 检查邮箱是否已存在。 */
    boolean existsByEmail(String email);

    /** 检查短标识符是否已存在（全局唯一）。 */
    boolean existsByShortId(String shortId);
}
