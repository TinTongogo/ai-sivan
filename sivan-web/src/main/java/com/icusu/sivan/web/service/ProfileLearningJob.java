package com.icusu.sivan.web.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户画像自动学习定时任务。
 * <p>每日凌晨 3:00 触发，从对话历史、记忆、工具使用等已有数据中
 * 批量提取兴趣标签，融合后写入 {@link com.icusu.sivan.domain.account.UserProfile#expertise}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileLearningJob {

    private final ProfileLearner profileLearner;

    @Scheduled(cron = "0 0 3 * * ?")
    public void runProfileLearning() {
        log.info("画像自动学习定时任务开始");
        long t0 = System.currentTimeMillis();
        try {
            profileLearner.runBatch();
        } catch (Exception e) {
            log.error("画像自动学习异常", e);
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("画像自动学习定时任务结束，耗时 {}ms", elapsed);
    }
}
