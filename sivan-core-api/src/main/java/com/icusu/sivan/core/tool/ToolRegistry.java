package com.icusu.sivan.core.tool;

import java.util.List;
import java.util.UUID;

/**
 * 工具注册中心端口：管理工具规格到执行器的映射。
 * <p>
 * 扩展方法（4.2 节）：
 * <ul>
 *   <li>{@link #listAvailable(UUID)} — 按账号获取可用工具列表（合并去重）</li>
 *   <li>{@link #findProvider(String)} — 按工具名查找提供者</li>
 * </ul>
 */
public interface ToolRegistry {

    void register(ToolSpec spec, ToolExecutor executor);

    ToolExecutor find(String name);

    List<ToolSpec> allSpecs();

    void unregister(String name);

    /** 获取指定账户可用的所有工具列表（合并去重，默认等价于 allSpecs）。 */
    default List<ToolSpec> listAvailable(UUID accountId) {
        return allSpecs();
    }

    /** 按名称查找工具对应的提供者。默认返回 null，由实现类覆盖。 */
    default ToolProvider findProvider(String toolName) {
        return null;
    }
}
