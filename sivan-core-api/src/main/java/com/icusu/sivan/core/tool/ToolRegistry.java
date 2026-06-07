package com.icusu.sivan.core.tool;

import java.util.List;

/**
 * 工具注册中心端口：管理工具规格到执行器的映射。
 */
public interface ToolRegistry {

    void register(ToolSpec spec, ToolExecutor executor);

    ToolExecutor find(String name);

    List<ToolSpec> allSpecs();

    void unregister(String name);
}
