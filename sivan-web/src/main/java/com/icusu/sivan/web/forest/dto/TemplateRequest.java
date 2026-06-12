package com.icusu.sivan.web.forest.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 模板创建/更新请求 */
public class TemplateRequest {
    private String name;
    private String description;
    /** 模板根节点 JSON */
    private Map<String, Object> rootNode;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getRootNode() { return rootNode; }
    public void setRootNode(Map<String, Object> rootNode) { this.rootNode = rootNode; }
}
