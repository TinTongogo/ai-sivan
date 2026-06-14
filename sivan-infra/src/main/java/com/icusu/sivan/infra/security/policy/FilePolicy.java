package com.icusu.sivan.infra.security.policy;

import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.domain.security.*;
import com.icusu.sivan.infra.file.FileSecurityManager;
import org.springframework.stereotype.Component;

/**
 * 文件策略 — 基于 FileSecurityManager 校验文件操作。
 * <p>
 * 校验规则：
 * <ul>
 *   <li>路径标准化 + 前缀检查（防路径穿越）</li>
 *   <li>跨项目目录隔离</li>
 *   <li>归档项目只读保护</li>
 *   <li>符号链接解析防逃逸</li>
 * </ul>
 */
@Component
public class FilePolicy implements Policy<Action> {

    private final FileSecurityManager fileSecurityManager;

    public FilePolicy(FileSecurityManager fileSecurityManager) {
        this.fileSecurityManager = fileSecurityManager;
    }

    @Override
    public void validate(Action action, SecurityContext ctx) {
        try {
            if (action instanceof FileRead read) {
                fileSecurityManager.validate(read.path(), ctx.projectRoot(), false,
                        FileSecurityManager.FileOperation.READ);
            } else if (action instanceof FileWrite write) {
                fileSecurityManager.validate(write.path(), ctx.projectRoot(), false,
                        FileSecurityManager.FileOperation.WRITE);
            } else {
                throw new PolicyViolationException("不支持的文件操作: " + action.getClass().getSimpleName());
            }
        } catch (DomainException e) {
            throw new PolicyViolationException(e.getMessage());
        }
    }

    @Override
    public Class<Action> actionType() {
        return Action.class;
    }

    @Override
    public String requiredPermission() {
        return "file";
    }
}
