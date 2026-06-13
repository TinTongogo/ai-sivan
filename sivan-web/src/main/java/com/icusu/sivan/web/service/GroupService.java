package com.icusu.sivan.web.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.infra.agent.entity.ProjectEntity;
import com.icusu.sivan.infra.agent.repository.ProjectJpaRepository;
import com.icusu.sivan.infra.agent.service.ShortIdGenerator;
import com.icusu.sivan.web.file.dto.FileEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 分组管理服务，管理项目分组。
 */
@Slf4j
@Service
public class GroupService {

    private final ProjectJpaRepository projectJpaRepository;
    private final IAccountRepository accountRepository;
    private final IConversationRepository conversationRepository;

    @Value("${sivan.file.root-path}")
    private String fileRootPath;

    public GroupService(ProjectJpaRepository projectJpaRepository, IAccountRepository accountRepository,
                        IConversationRepository conversationRepository) {
        this.projectJpaRepository = projectJpaRepository;
        this.accountRepository = accountRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * 查询分组列表。
     */
    public List<ProjectEntity> list(UUID accountId) {
        return projectJpaRepository.findByAccountIdOrderBySortOrderAsc(accountId);
    }

    /**
     * 查询账户下的项目数量。
     */
    public long countByAccount(UUID accountId) {
        return projectJpaRepository.countByAccountId(accountId);
    }

    /**
     * 保存分组（供其他 Service 调用）。
     */
    public ProjectEntity save(ProjectEntity entity) {
        return projectJpaRepository.save(entity);
    }

    /**
     * 创建分组。
     */
    public ProjectEntity create(UUID accountId, String name) {
        return create(accountId, name, true);
    }

    /**
     * 创建分组（可选自动创建本地目录）。
     */
    public ProjectEntity create(UUID accountId, String name, boolean autoCreateDir) {
        long count = projectJpaRepository.countByAccountId(accountId);
        String shortId = generateShortIdForAccount(accountId);
        ProjectEntity entity = ProjectEntity.builder()
                .accountId(accountId)
                .name(name)
                .shortId(shortId)
                .sortOrder((int) count)
                .build();
        entity = projectJpaRepository.save(entity);
        if (autoCreateDir) {
            String dir = initProjectDirectory(accountId, shortId);
            entity.setLocalPath(dir);
            entity.setLocalPathAuto(true);
            entity = projectJpaRepository.save(entity);
        }
        log.info("项目创建成功: name={} shortId={} localPath={}", name, shortId, entity.getLocalPath());
        return entity;
    }

    /**
     * 重命名分组。
     */
    public ProjectEntity rename(UUID accountId, UUID groupId, String name) {
        ProjectEntity entity = findOwned(accountId, groupId);
        entity.setName(name);
        return projectJpaRepository.save(entity);
    }

    /**
     * 删除分组（默认保留文件）。
     */
    public void delete(UUID accountId, UUID groupId) {
        delete(accountId, groupId, false);
    }

    /**
     * 删除分组，可选同步清理本地文件目录（不可恢复）。
     * 同时级联删除项目下的对话和消息。
     */
    @Transactional
    public void delete(UUID accountId, UUID groupId, boolean removeFiles) {
        ProjectEntity entity = findOwned(accountId, groupId);
        log.info("项目删除: name={} shortId={} localPath={} removeFiles={}", entity.getName(), entity.getShortId(), entity.getLocalPath(), removeFiles);
        if (removeFiles && entity.getLocalPath() != null) {
            deleteDirectory(resolveLocalPath(entity.getLocalPath()));
        }
        // 先删对话，再删项目（不级联删除消息）
        var conversations = conversationRepository.findAllByAccountAndProject(accountId, groupId);
        for (var conv : conversations) {
            conversationRepository.delete(conv.getConversationId());
        }
        projectJpaRepository.delete(entity);
    }

    /**
     * 将存储的相对 localPath 解析为运行时绝对路径。
     * 所有 DB 存储的路径均为相对路径，运行时拼上 root-path 供文件系统操作。
     */
    private String resolveLocalPath(String localPath) {
        if (localPath == null || localPath.isBlank()) return null;
        return Paths.get(fileRootPath).resolve(localPath).normalize().toString();
    }

    /**
     * 递归删除目录树。删除前发出日志，删除后检查残留。
     */
    private void deleteDirectory(String path) {
        try {
            Path dir = Paths.get(path).normalize().toAbsolutePath();
            if (!Files.exists(dir)) return;
            try (var walk = Files.walk(dir).sorted((a, b) -> b.toString().length() - a.toString().length())) {
                walk.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("删除失败: {}", p);
                    }
                });
            }
            log.info("项目目录已删除: {}", dir);
        } catch (IOException e) {
            log.warn("删除项目目录失败: {}", e.getMessage());
        }
    }

    /**
     * 为新项目创建本地目录结构，返回相对路径（不绑定 root-path，换服务器不失效）。
     */
    public String initProjectDirectory(UUID accountId, String projectShortId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) throw new DomainException("账户短标识符未生成，请联系管理员");
        Path root = Paths.get(fileRootPath).resolve(acctShortId).resolve(projectShortId);
        try {
            Files.createDirectories(root);
            log.info("项目目录已创建: acctShortId={} projectShortId={} root={}", acctShortId, projectShortId, root);
            // 存相对路径：{acctShortId}/{projectShortId}，运行时通过 resolveLocalPath 拼 root-path
            return Paths.get(acctShortId, projectShortId).toString();
        } catch (IOException e) {
            throw new DomainException("创建项目目录失败: " + e.getMessage());
        }
    }

    /**
     * 获取分组本地路径下的文件列表。
     */
    public List<FileEntryResponse> listFiles(UUID accountId, UUID groupId, String subPath) {
        ProjectEntity entity = findOwned(accountId, groupId);
        String localPath = resolveLocalPath(entity.getLocalPath());
        if (localPath == null || localPath.isBlank()) {
            throw new DomainException("未配置本地路径");
        }

        Path root = Paths.get(localPath).normalize().toAbsolutePath();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new DomainException("本地路径不存在或不是目录: " + root);
        }

        Path target = subPath != null && !subPath.isBlank()
                ? root.resolve(subPath).normalize()
                : root;

        if (!target.startsWith(root)) {
            throw new DomainException("无效的路径");
        }
        if (!Files.exists(target) || !Files.isDirectory(target)) {
            throw new DomainException("目录不存在: " + target);
        }

        List<FileEntryResponse> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(target)) {
            stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).forEach(p -> {
                try {
                    entries.add(FileEntryResponse.builder()
                            .name(p.getFileName().toString())
                            .directory(Files.isDirectory(p))
                            .size(Files.isRegularFile(p) ? Files.size(p) : 0)
                            .lastModified(Files.getLastModifiedTime(p).toMillis())
                            .build());
                } catch (IOException e) {
                    log.warn("读取文件属性失败: {}", p);
                }
            });
        } catch (IOException e) {
            throw new DomainException("读取目录失败: " + e.getMessage());
        }

        return entries;
    }

    /**
     * 归档项目：设为只读，不允许创建新对话或修改文件。
     */
    public ProjectEntity archive(UUID accountId, UUID groupId) {
        ProjectEntity entity = findOwned(accountId, groupId);
        if (Boolean.TRUE.equals(entity.getArchived())) {
            throw new DomainException("项目已归档");
        }
        entity.setArchived(true);
        entity.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return projectJpaRepository.save(entity);
    }

    /**
     * 取消归档：恢复读写。
     */
    public ProjectEntity unarchive(UUID accountId, UUID groupId) {
        ProjectEntity entity = findOwned(accountId, groupId);
        if (!Boolean.TRUE.equals(entity.getArchived())) {
            throw new DomainException("项目未归档");
        }
        entity.setArchived(false);
        entity.setArchivedAt(null);
        return projectJpaRepository.save(entity);
    }

    /**
     * 为账户生成唯一的短标识符。
     */
    private String generateShortIdForAccount(UUID accountId) {
        for (int i = 0; i < 5; i++) {
            String candidate = ShortIdGenerator.generate();
            if (!projectJpaRepository.existsByAccountIdAndShortId(accountId, candidate)) {
                return candidate;
            }
        }
        return ShortIdGenerator.generateWithSuffix();
    }

    /**
     * 获取账户的短标识符。
     */
    public String getAccountShortId(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getShortId)
                .orElse(null);
    }

    /**
     * 获取项目根目录路径：{fileRootPath}/{acctShortId}/{projectShortId}，无项目时返回账户路径
     */
    public String getProjectRootPath(UUID accountId, UUID projectId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) {
            throw new DomainException("账户短标识符未生成，请联系管理员");
        }
        if (projectId == null) {
            return Paths.get(fileRootPath).resolve(acctShortId).toString();
        }
        ProjectEntity project = findOwned(accountId, projectId);
        String projShortId = project.getShortId();
        if (projShortId == null) {
            throw new DomainException("项目短标识符未生成，请更新项目或联系管理员");
        }
        return Paths.get(fileRootPath).resolve(acctShortId)
                .resolve(projShortId).toString();
    }

    /**
     * 获取项目展示路径：{acctShortId}/{projectShortId}（不暴露 root-path）。
     */
    public String getProjectDisplayPath(UUID accountId, UUID projectId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) {
            throw new DomainException("账户短标识符未生成，请联系管理员");
        }
        if (projectId == null) {
            return acctShortId;
        }
        ProjectEntity project = findOwned(accountId, projectId);
        String projShortId = project.getShortId();
        if (projShortId == null) {
            throw new DomainException("项目短标识符未生成，请更新项目或联系管理员");
        }
        return Paths.get(acctShortId, projShortId).toString();
    }

    /**
     * 在系统文件管理器中打开项目本地目录。
     */
    public void openDirectory(UUID accountId, UUID groupId) {
        ProjectEntity entity = findOwnedInternal(accountId, groupId);
        String localPath = resolveLocalPath(entity.getLocalPath());
        if (localPath == null || localPath.isBlank()) {
            throw new DomainException("未配置本地路径");
        }
        Path path = Paths.get(localPath).normalize().toAbsolutePath();
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new DomainException("本地路径不存在或不是目录: " + path);
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", path.toString()).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("explorer", path.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", path.toString()).start();
            }
            log.info("已在系统文件管理器打开目录: path={}", path);
        } catch (IOException e) {
            throw new DomainException("打开目录失败: " + e.getMessage());
        }
    }

    /**
     * 公开查询方法：校验所有权并返回项目。
     */
    public ProjectEntity findOwned(UUID accountId, UUID groupId) {
        return findOwnedInternal(accountId, groupId);
    }

    /**
     * 查找并校验当前用户拥有的分组。
     */
    private ProjectEntity findOwnedInternal(UUID accountId, UUID groupId) {
        ProjectEntity entity = projectJpaRepository.findById(groupId)
                .orElseThrow(() -> new DomainException("分组不存在"));
        if (!entity.getAccountId().equals(accountId)) {
            throw new DomainException("无权操作");
        }
        return entity;
    }
}
