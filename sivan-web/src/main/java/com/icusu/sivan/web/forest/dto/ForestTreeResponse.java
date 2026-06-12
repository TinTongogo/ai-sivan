package com.icusu.sivan.web.forest.dto;

import java.util.List;

/**
 * Forest 执行树响应 — 供前端 PipelineDialog 展示。
 */
public class ForestTreeResponse {

    private String forestId;
    private TreeNode root;
    private Progress progress;

    public String getForestId() { return forestId; }
    public void setForestId(String forestId) { this.forestId = forestId; }
    public TreeNode getRoot() { return root; }
    public void setRoot(TreeNode root) { this.root = root; }
    public Progress getProgress() { return progress; }
    public void setProgress(Progress progress) { this.progress = progress; }

    public static class A2aMessage {
        private String sourceAgent;
        private String targetAgent;
        private String content;
        private String messageType;

        public A2aMessage() {}
        public A2aMessage(String source, String target, String content, String type) {
            this.sourceAgent = source; this.targetAgent = target;
            this.content = content; this.messageType = type;
        }
        public String getSourceAgent() { return sourceAgent; }
        public void setSourceAgent(String sourceAgent) { this.sourceAgent = sourceAgent; }
        public String getTargetAgent() { return targetAgent; }
        public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
    }

    public static class TreeNode {
        private String nodeId;
        private String name;
        private String status;
        private String mode;
        private String agent;
        private boolean leaf;
        private List<A2aMessage> a2aMessages;
        private Integer durationMs;
        private Integer tokens;
        private Integer routeTier;
        private Double routeConfidence;
        private List<TreeNode> children;

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getAgent() { return agent; }
        public void setAgent(String agent) { this.agent = agent; }
        public boolean isLeaf() { return leaf; }
        public void setLeaf(boolean leaf) { this.leaf = leaf; }
        public Integer getDurationMs() { return durationMs; }
        public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
        public Integer getTokens() { return tokens; }
        public void setTokens(Integer tokens) { this.tokens = tokens; }
        public Integer getRouteTier() { return routeTier; }
        public void setRouteTier(Integer routeTier) { this.routeTier = routeTier; }
        public Double getRouteConfidence() { return routeConfidence; }
        public void setRouteConfidence(Double routeConfidence) { this.routeConfidence = routeConfidence; }
        public List<A2aMessage> getA2aMessages() { return a2aMessages; }
        public void setA2aMessages(List<A2aMessage> a2aMessages) { this.a2aMessages = a2aMessages; }
        public List<TreeNode> getChildren() { return children; }
        public void setChildren(List<TreeNode> children) { this.children = children; }
    }

    public static class Progress {
        private int completed;
        private int total;

        public Progress() {}
        public Progress(int completed, int total) { this.completed = completed; this.total = total; }

        public int getCompleted() { return completed; }
        public void setCompleted(int completed) { this.completed = completed; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
    }
}
