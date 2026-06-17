package com.icusu.sivan.infra.forest.repository;

import com.icusu.sivan.infra.forest.entity.IntentPrototypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 意图原型配置数据访问接口。
 */
@Repository
public interface IntentPrototypeJpaRepository extends JpaRepository<IntentPrototypeEntity, String> {

    Optional<IntentPrototypeEntity> findByPrototypeKey(String prototypeKey);
}
