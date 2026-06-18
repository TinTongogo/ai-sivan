package com.icusu.sivan.application.service;

import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.agent.Project;
import com.icusu.sivan.domain.agent.repository.IProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 项目目录扫描器 — 扫描 {@code sivan.file.root-path} 下已有的项目目录，
 * 发现磁盘上存在但数据库中无对应记录的项目（orphan 目录），可选择性地创建 DB 记录。
 * <p>
 * 目录结构：{rootPath}/{acctShortId}/{projectShortId}/
 */
@Service
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private final Path rootPath;
    private final IAccountRepository accountRepository;
    private final IProjectRepository projectRepository;

    public ProjectScanner(@Value("${sivan.file.root-path}") String fileRootPath,
                          IAccountRepository accountRepository,
                          IProjectRepository projectRepository) {
        this.rootPath = Paths.get(fileRootPath).normalize().toAbsolutePath();
        this.accountRepository = accountRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * 扫描文件系统，为每个 orphan 项目目录创建对应的 DB 记录。
     * <p>
     * 启动时自动执行一次（仅日志，不创建），可通过 {@link #scanAndCreateProjects()} 显式触发创建。
     *
     * @return 本次新创建的项目数量
     */
    public int scanAndCreateProjects() {
        List<Project> toCreate = discoverOrphanProjects();
        if (toCreate.isEmpty()) {
            log.info("项目目录扫描完成：无 orphan 项目");
            return 0;
        }
        int created = 0;
        for (Project p : toCreate) {
            try {
                projectRepository.save(p);
                created++;
                log.info("项目扫描恢复: 已创建项目记录 accountId={} shortId={} localPath={}",
                        p.getAccountId(), p.getShortId(), p.getLocalPath());
            } catch (Exception e) {
                log.warn("项目扫描恢复失败: shortId={} error={}", p.getShortId(), e.getMessage());
            }
        }
        log.info("项目目录扫描完成: 共发现 {} 个 orphan 目录，已创建 {} 个项目记录", toCreate.size(), created);
        return created;
    }

    /**
     * 启动时扫描，仅记录 orphan 目录信息，不自动创建。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!Files.isDirectory(rootPath)) {
            log.debug("项目根目录不存在，跳过启动扫描: {}", rootPath);
            return;
        }
        List<Project> orphans = discoverOrphanProjects();
        if (orphans.isEmpty()) {
            log.info("启动扫描: 项目目录与数据库记录一致");
        } else {
            log.warn("启动扫描: 发现 {} 个 orphan 项目目录（磁盘存在，DB 无记录），"
                    + "可调用 ProjectScanner.scanAndCreateProjects() 恢复",
                    orphans.size());
            for (Project p : orphans) {
                log.warn("  orphan 项目: acctShortId={} projectShortId={}",
                        Paths.get(p.getLocalPath()).getParent(), p.getShortId());
            }
        }
    }

    // ====== 内部逻辑 ======

    /**
     * 扫描文件系统，发现 orphan 项目目录。
     * 仅统计不落库，返回待创建的 Project 对象列表。
     */
    List<Project> discoverOrphanProjects() {
        if (!Files.isDirectory(rootPath)) {
            log.debug("项目根目录不存在: {}", rootPath);
            return List.of();
        }
        List<Project> orphans = new ArrayList<>();

        try (Stream<Path> accountDirs = Files.list(rootPath)) {
            List<Path> accounts = accountDirs.filter(Files::isDirectory).toList();

            for (Path acctDir : accounts) {
                String acctShortId = acctDir.getFileName().toString();
                var accountOpt = accountRepository.findByShortId(acctShortId);
                if (accountOpt.isEmpty()) {
                    log.debug("跳过未知目录（无对应账户）: {}", acctShortId);
                    continue;
                }
                var account = accountOpt.get();
                UUID accountId = account.getAccountId();

                try (Stream<Path> projectDirs = Files.list(acctDir)) {
                    List<Path> projects = projectDirs.filter(Files::isDirectory).toList();
                    for (Path projDir : projects) {
                        String projShortId = projDir.getFileName().toString();
                        if (projectRepository.existsByAccountIdAndShortId(accountId, projShortId)) {
                            continue; // 已存在
                        }
                        String localPath = acctShortId + "/" + projShortId;
                        int nextSort = (int) projectRepository.countByAccountId(accountId);
                        Project orphan = Project.builder()
                                .accountId(accountId)
                                .name(projShortId)
                                .shortId(projShortId)
                                .localPath(localPath)
                                .localPathAuto(true)
                                .sortOrder(nextSort)
                                .build();
                        orphans.add(orphan);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描项目目录异常: {}", e.getMessage());
        }
        return orphans;
    }
}
