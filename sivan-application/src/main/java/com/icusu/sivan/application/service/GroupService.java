package com.icusu.sivan.application.service;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.account.Account;
import com.icusu.sivan.domain.account.IAccountRepository;
import com.icusu.sivan.domain.agent.Project;
import com.icusu.sivan.domain.agent.repository.IProjectRepository;
import com.icusu.sivan.domain.conversation.IConversationRepository;
import com.icusu.sivan.infra.agent.service.ShortIdGenerator;
import com.icusu.sivan.application.file.dto.FileEntryResponse;
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

    private final IProjectRepository projectRepository;
    private final IAccountRepository accountRepository;
    private final IConversationRepository conversationRepository;

    @Value("${sivan.file.root-path}")
    private String fileRootPath;

    public GroupService(IProjectRepository projectRepository, IAccountRepository accountRepository,
                        IConversationRepository conversationRepository) {
        this.projectRepository = projectRepository;
        this.accountRepository = accountRepository;
        this.conversationRepository = conversationRepository;
    }

    public List<Project> list(UUID accountId) {
        return projectRepository.findByAccountIdOrderBySortOrderAsc(accountId);
    }

    public long countByAccount(UUID accountId) {
        return projectRepository.countByAccountId(accountId);
    }

    public Project create(UUID accountId, String name) {
        return create(accountId, name, true);
    }

    public Project create(UUID accountId, String name, boolean autoCreateDir) {
        long count = projectRepository.countByAccountId(accountId);
        String shortId = generateShortIdForAccount(accountId);
        Project project = Project.builder()
                .accountId(accountId)
                .name(name)
                .shortId(shortId)
                .sortOrder((int) count)
                .build();
        project = projectRepository.save(project);
        if (autoCreateDir) {
            String dir = initProjectDirectory(accountId, shortId);
            project.setLocalPath(dir);
            project.setLocalPathAuto(true);
            project = projectRepository.save(project);
        }
        log.info("项目创建成功: name={} shortId={} localPath={}", name, shortId, project.getLocalPath());
        return project;
    }

    public Project rename(UUID accountId, UUID groupId, String name) {
        Project project = findOwned(accountId, groupId);
        project.setName(name);
        return projectRepository.save(project);
    }

    public void delete(UUID accountId, UUID groupId) {
        delete(accountId, groupId, false);
    }

    @Transactional
    public void delete(UUID accountId, UUID groupId, boolean removeFiles) {
        Project project = findOwned(accountId, groupId);
        log.info("项目删除: name={} shortId={} localPath={} removeFiles={}", project.getName(), project.getShortId(), project.getLocalPath(), removeFiles);
        if (removeFiles && project.getLocalPath() != null) {
            deleteDirectory(resolveLocalPath(project.getLocalPath()));
        }
        var conversations = conversationRepository.findAllByAccountAndProject(accountId, groupId);
        for (var conv : conversations) {
            conversationRepository.delete(conv.getConversationId());
        }
        projectRepository.delete(project);
    }

    private String resolveLocalPath(String localPath) {
        if (localPath == null || localPath.isBlank()) return null;
        return Paths.get(fileRootPath).resolve(localPath).normalize().toString();
    }

    private void deleteDirectory(String path) {
        try {
            Path dir = Paths.get(path).normalize().toAbsolutePath();
            if (!Files.exists(dir)) return;
            try (var walk = Files.walk(dir).sorted((a, b) -> b.toString().length() - a.toString().length())) {
                walk.forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("删除失败: {}", p); }
                });
            }
            log.info("项目目录已删除: {}", dir);
        } catch (IOException e) {
            log.warn("删除项目目录失败: {}", e.getMessage());
        }
    }

    public String initProjectDirectory(UUID accountId, String projectShortId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) throw new DomainException("账户短标识符未生成，请联系管理员");
        Path root = Paths.get(fileRootPath).resolve(acctShortId).resolve(projectShortId);
        try {
            Files.createDirectories(root);
            log.info("项目目录已创建: acctShortId={} projectShortId={} root={}", acctShortId, projectShortId, root);
            return Paths.get(acctShortId, projectShortId).toString();
        } catch (IOException e) {
            throw new DomainException("创建项目目录失败: " + e.getMessage());
        }
    }

    public List<FileEntryResponse> listFiles(UUID accountId, UUID groupId, String subPath) {
        Project project = findOwned(accountId, groupId);
        String localPath = resolveLocalPath(project.getLocalPath());
        if (localPath == null || localPath.isBlank()) throw new DomainException("未配置本地路径");
        Path root = Paths.get(localPath).normalize().toAbsolutePath();
        if (!Files.exists(root) || !Files.isDirectory(root)) throw new DomainException("本地路径不存在或不是目录: " + root);
        Path target = subPath != null && !subPath.isBlank() ? root.resolve(subPath).normalize() : root;
        if (!target.startsWith(root)) throw new DomainException("无效的路径");
        if (!Files.exists(target) || !Files.isDirectory(target)) throw new DomainException("目录不存在: " + target);
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
                } catch (IOException e) { log.warn("读取文件属性失败: {}", p); }
            });
        } catch (IOException e) { throw new DomainException("读取目录失败: " + e.getMessage()); }
        return entries;
    }

    public Project archive(UUID accountId, UUID groupId) {
        Project project = findOwned(accountId, groupId);
        if (Boolean.TRUE.equals(project.getArchived())) throw new DomainException("项目已归档");
        project.setArchived(true);
        project.setArchivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return projectRepository.save(project);
    }

    public Project unarchive(UUID accountId, UUID groupId) {
        Project project = findOwned(accountId, groupId);
        if (!Boolean.TRUE.equals(project.getArchived())) throw new DomainException("项目未归档");
        project.setArchived(false);
        project.setArchivedAt(null);
        return projectRepository.save(project);
    }

    private String generateShortIdForAccount(UUID accountId) {
        for (int i = 0; i < 5; i++) {
            String candidate = ShortIdGenerator.generate();
            if (!projectRepository.existsByAccountIdAndShortId(accountId, candidate)) return candidate;
        }
        return ShortIdGenerator.generateWithSuffix();
    }

    public String getAccountShortId(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::getShortId).orElse(null);
    }

    public String getProjectRootPath(UUID accountId, UUID projectId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) throw new DomainException("账户短标识符未生成，请联系管理员");
        if (projectId == null) return Paths.get(fileRootPath).resolve(acctShortId).toString();
        Project project = findOwned(accountId, projectId);
        String projShortId = project.getShortId();
        if (projShortId == null) throw new DomainException("项目短标识符未生成，请更新项目或联系管理员");
        return Paths.get(fileRootPath).resolve(acctShortId).resolve(projShortId).toString();
    }

    public String getProjectDisplayPath(UUID accountId, UUID projectId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("账户不存在"));
        String acctShortId = account.getShortId();
        if (acctShortId == null) throw new DomainException("账户短标识符未生成，请联系管理员");
        if (projectId == null) return acctShortId;
        Project project = findOwned(accountId, projectId);
        String projShortId = project.getShortId();
        if (projShortId == null) throw new DomainException("项目短标识符未生成，请更新项目或联系管理员");
        return Paths.get(acctShortId, projShortId).toString();
    }

    public void openDirectory(UUID accountId, UUID groupId) {
        Project project = findOwned(accountId, groupId);
        String localPath = resolveLocalPath(project.getLocalPath());
        if (localPath == null || localPath.isBlank()) throw new DomainException("未配置本地路径");
        Path path = Paths.get(localPath).normalize().toAbsolutePath();
        if (!Files.exists(path) || !Files.isDirectory(path)) throw new DomainException("本地路径不存在或不是目录: " + path);
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) new ProcessBuilder("open", path.toString()).start();
            else if (os.contains("win")) new ProcessBuilder("explorer", path.toString()).start();
            else new ProcessBuilder("xdg-open", path.toString()).start();
            log.info("已在系统文件管理器打开目录: path={}", path);
        } catch (IOException e) { throw new DomainException("打开目录失败: " + e.getMessage()); }
    }

    public Project findOwned(UUID accountId, UUID groupId) {
        Project project = projectRepository.findById(groupId)
                .orElseThrow(() -> new DomainException("分组不存在"));
        if (!project.getAccountId().equals(accountId)) throw new DomainException("无权操作");
        return project;
    }
}
