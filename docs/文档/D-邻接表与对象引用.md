# 邻接表 + 对象引用

## 核心问题

树结构是森林架构的核心表达方式。一棵执行树有根节点（TaskNode）、内层节点（InnerGoalNode）、叶子节点（多个 TaskNode）。系统需要同时支持两种访问模式：

1. **持久化查询**：从数据库加载一棵完整的执行树
2. **运行时遍历**：在内存中从父节点导航到子节点，或从子节点导航到父节点

这就需要两套相互映射的树结构——**邻接表（Adjacency List）** 在数据库中表达树，**对象引用（Object Reference）** 在运行时表达树。

---

## 1. 什么是邻接表

邻接表是关系数据库中最常见的树存储模式。每行记录持有一个指向其父行的指针：

```sql
CREATE TABLE forest_nodes (
    node_id         VARCHAR(36) PRIMARY KEY,  -- 自身 ID
    parent_node_id  VARCHAR(36),               -- 父节点 ID（邻接表指针）
    sort_order      INT NOT NULL,              -- 兄弟节点排序
    ...
    -- 自引用外键（当前活跃）
    CONSTRAINT forest_nodes_parent_node_id_fkey
        FOREIGN KEY (parent_node_id) REFERENCES forest_nodes(node_id)
        ON DELETE CASCADE
);
```

每一行就是一个"邻接表条目"：`node_id → parent_node_id`。从任意节点出发，通过反复查询 `parent_node_id` 可以追溯到根节点；通过查询 `parent_node_id = ?` 可以找到所有子节点。

### 邻接表在项目中的具体体现

```
forest_nodes 表
┌─────────────────────────────┬──────────────────┬──────────────────┐
│ node_id                     │ parent_node_id    │ node_type        │
├─────────────────────────────┼──────────────────┼──────────────────┤
│ conv-1234                   │ NULL              │ conversation     │  ← 根
│ tree-root-1                 │ conv-1234         │ task             │  ← 执行树 1
│ inner-1                     │ tree-root-1       │ inner_goal       │
│ task-a                      │ inner-1           │ task             │
│ task-b                      │ inner-1           │ task             │
│ msg-user-1                  │ tree-root-1       │ message          │  ← 用户消息挂到执行树
│ msg-asst-1                  │ tree-root-1       │ message          │  ← 助理消息挂到执行树
│ tree-root-2                 │ conv-1234         │ task             │  ← 执行树 2（下一轮）
└─────────────────────────────┴──────────────────┴──────────────────┘
```

**关键观察**：对话容器（`node_type='conversation'`）是整棵森林的根，`parent_node_id = NULL`。所有执行树根节点和消息节点都是它的子节点。

---

## 2. 什么是对象引用

在 Java 运行时，树通过对象引用表达：

```java
// TreeNode 接口定义
public interface TreeNode {
    String nodeId();
    TreeNode parent();         // 对象引用 → 父节点
    List<TreeNode> children(); // 对象引用 → 子节点列表
    int order();               // 兄弟节点中的序号
    // ...
}
```

这不是一个"扁平"的数据结构——`parent()` 返回的是同一个 Java 对象的引用，`children()` 返回的是子对象列表的引用。整棵树在内存中是一个**连通的对象图**：

```
heap 中的对象图：

TreeNode@1234 (conv-1234)
  parent = null
  children = [TreeNode@2345, TreeNode@3456]
  
  TreeNode@2345 (tree-root-1)          TreeNode@3456 (tree-root-2)
    parent = TreeNode@1234               parent = TreeNode@1234
    children = [TreeNode@4567]           children = []
    
    TreeNode@4567 (inner-1)
      parent = TreeNode@2345
      children = [TreeNode@5678, TreeNode@6789]
      
      TreeNode@5678 (task-a)          TreeNode@6789 (task-b)
        parent = TreeNode@4567          parent = TreeNode@4567
        children = []                   children = []
```

### 运行时遍历

```java
// 从根节点向下遍历（递归）
void traverse(TreeNode node) {
    System.out.println(node.nodeId() + " → " + node.content());
    for (var child : node.children()) {
        traverse(child);  // 通过对象引用导航到子节点
    }
}

// 从叶子节点向上追溯（实用场景：找到执行树的根）
String findRootNodeId(TreeNode leaf) {
    TreeNode current = leaf;
    while (current.parent() != null) {
        current = current.parent();  // 通过对象引用导航到父节点
    }
    return current.nodeId();
}
```

---

## 3. 两者的映射关系

### 3.1 从邻接表到对象引用（加载）

`ForestRepositoryAdapter.assembleTree()` 实现了从扁平行列表到连通对象图的转换：

```java
private TreeNode assembleTree(List<ForestNodeEntity> rows, UUID forestId) {
    // 第一遍扫描：创建所有节点对象，按 node_id 建立索引
    Map<String, TreeNode> nodeMap = new LinkedHashMap<>();
    Map<String, List<String>> parentToChildren = new HashMap<>();
    
    for (var row : rows) {
        TreeNode node = createNode(row);         // 创建 Java 对象
        nodeMap.put(row.getNodeId(), node);       // node_id → 对象引用
        if (row.getParentNodeId() != null) {
            parentToChildren.computeIfAbsent(...) // parent_node_id → child_node_ids
                .add(row.getNodeId());
        }
    }
    
    // 第二遍扫描：连接父子关系（建立对象引用）
    for (var entry : parentToChildren.entrySet()) {
        TreeNode parent = nodeMap.get(entry.getKey());  // 从索引取父节点
        List<TreeNode> children = entry.getValue().stream()
            .map(nodeMap::get)                          // 从索引取子节点
            .filter(Objects::nonNull)
            .collect(toList());
        // 双向绑定：
        for (int i = 0; i < children.size(); i++) {
            children.get(i).setParent(parent);   // child.parent = parent
            children.get(i).setOrder(i);         // child.order = i
        }
        parent.replaceChildren(children);         // parent.children = [child1, child2, ...]
    }
    
    return nodeMap.values().stream().findFirst().orElse(null);
}
```

**为什么两遍扫描？** 因为邻接表中的行是无序的。子节点可能在父节点之前出现。两遍扫描保证所有节点先被创建，然后再建立引用关系。

### 3.2 从对象引用到邻接表（持久化）

```java
// ForestRepositoryAdapter.collectNodes() — 递归遍历对象图，生成扁平行列表
private void collectNodes(TreeNode node, UUID forestId, List<ForestNodeEntity> result) {
    result.add(toEntity(node, forestId));          // 当前节点 → 行
    for (TreeNode child : node.children()) {
        collectNodes(child, forestId, result);      // 递归子节点（通过对象引用导航）
    }
}

// toEntity() — 将对象引用转为邻接表 parent_node_id
private ForestNodeEntity toEntity(TreeNode node, UUID forestId) {
    return ForestNodeEntity.builder()
        .nodeId(node.nodeId())
        .parentNodeId(resolveParentNodeId(node, forestId))  // 关键转换
        // ...
        .build();
}

// 解析父节点 ID：从对象引用到外键值
private static String resolveParentNodeId(TreeNode node, UUID forestId) {
    if (node.parent() != null) return node.parent().nodeId();  // 对象引用 → 邻接表指针
    return forestId.toString();  // 根节点挂到对话容器
}
```

---

## 4. 邻接表的补充：递归 CTE

邻接表的一个核心缺陷：**查询子树需要递归**。单条 SQL 语句无法直接"给我整棵子树"。

PostgreSQL 的递归 CTE（Common Table Expression）解决这个问题：

```sql
WITH RECURSIVE subtree AS (
    -- 锚点：找到根节点
    SELECT *, 0 AS depth FROM forest_nodes
    WHERE node_id = :rootNodeId AND forest_id = :forestId
    
    UNION ALL
    
    -- 递归：通过 parent_node_id 找到所有子节点
    SELECT fn.*, st.depth + 1 FROM forest_nodes fn
    INNER JOIN subtree st ON fn.parent_node_id = st.node_id
    WHERE fn.forest_id = :forestId AND st.depth < 1000  -- 深度保护
)
SELECT * FROM subtree ORDER BY sort_order;
```

### 为什么不用其他树结构？

| 模型 | 子树查询 | 插入/移动 | 本项目是否适用 |
|------|---------|----------|--------------|
| **邻接表** | 递归 CTE（O(depth)） | O(1) | ✅ 树结构动态生成 |
| **嵌套集** | O(1)（左右值 BETWEEN） | O(n) 重建 | ❌ 执行树频繁变更 |
| **闭包表** | O(1)（祖先表 JOIN） | O(depth) | ❌ 多一次存储维护 |
| **物化路径** | 前缀 LIKE 查询 | O(1) | ❌ 路径字符串长度不可控 |

**邻接表入选原因**：
- 插入成本最低——执行树是每次请求动态构建的
- 修改成本最低——节点状态更新不需要移动子树
- 与对象引用模型天然匹配——`parent_node_id` 一对一映射到 `TreeNode.parent()`

---

## 5. 对象引用在项目中的具体使用

### 5.1 树遍历

```java
// ForestExecutor 递归遍历执行树
void executeTree(ExecutableNode node, ExecutionContext ctx) {
    // 从 node.parent() 获取父节点信息（编排模式、兄弟节点状态）
    Mode mode = node.mode();
    
    // 通过 node.children() 递归
    if (mode != null && mode != Mode.NONE) {
        var strategy = modeDispatcher.dispatch(mode);
        strategy.execute(node, ctx, this, sink);  // 策略内遍历 children
    } else if (node.isLeaf()) {
        var executor = leafExecutorRegistry.find(node.nodeType());
        executor.execute(node, ctx, sink);         // 叶子节点执行
    }
}
```

### 5.2 兄弟节点查找

```java
// 通过 parent() 获取父节点，再从父节点的 children() 找下一个 PENDING 兄弟
public TreeNode findNextSibling(String nodeId, UUID forestId, UUID accountId) {
    var entity = forestNodeJpaRepository.findById(nodeId).orElse(null);
    if (entity == null) return null;
    String parentNodeId = entity.getParentNodeId();
    if (parentNodeId == null) return null;  // 根节点无兄弟
    
    // 通过邻接表查询 DB（因为对象引用只在加载的子树内有效）
    var next = forestNodeJpaRepository.findNextPendingSibling(
        parentNodeId, forestId, entity.getSortOrder());
    return next != null ? createNode(next) : null;
}
```

### 5.3 状态级联

```java
// CompressibleNode 的缓存失效向上传播
default void invalidateAncestorTokenCache() {
    TreeNode p = this.parent();  // 通过对象引用向上追溯
    while (p instanceof CompressibleNode cp) {
        cp.invalidateTokenCache();  // 逐层清除缓存
        p = p.parent();
    }
}
```

---

## 6. 边界情况

### 6.1 树的扁平化

对象引用图在内存中是连通的，但持久化时需要扁平化为无序的行列表。`collectNodes()` 通过**深度优先遍历**完成这个转换：

```java
// 输入：对象图（连通）
//   TaskNode("分析") → [InnerGoalNode(并行) → [TaskNode("技术"), TaskNode("市场")]]
//
// 输出：扁平行列表（无序）
//   1. task, "分析", parent=convId
//   2. inner_goal, "", parent=task("分析")
//   3. task, "技术", parent=inner_goal
//   4. task, "市场", parent=inner_goal
```

### 6.2 部分树加载

并非所有场景都需要加载整棵树。消息分页只需要加载消息节点（不加载执行树）：

```java
// 只加载消息：node_type='message' 的过滤查询
List<ForestNodeEntity> messages = forestNodeJpaRepository
    .findLatestMessages(forestId, PageRequest.of(0, limit));
// 返回的是扁平行列表，每个消息的 parentNodeId 指向执行树根
// 但执行树的完整对象图不会被加载
```

这种情况下，消息的 `parent()` 返回 `null`（因为父节点没有被加载到对象图中）。这是**预期行为**——部分加载时对象引用不完整。

### 6.3 消息的 parent 问题

这是本项目中最隐蔽的 bug 源头。消息节点通过邻接表（`parent_node_id`）指向执行树根，但对象引用（`parent()`）可能不存在：

```java
// getMessageForest() 中的关键判断
String treeRootNodeId = forestNodeJpaRepository.findById(messageId.toString())
    .map(ForestNodeEntity::getParentNodeId)  // 从邻接表读取
    .filter(c -> c != null && !c.isBlank())
    .orElse(null);

// 如果 treeRootNodeId != null，加载整棵子树（建立完整的对象引用）
if (treeRootNodeId != null) {
    root = forestRepository.findSubtree(treeRootNodeId, accountId);  // 递归 CTE
    // 此时 root（执行树根）的 children 中包含消息节点
    // 消息节点的 parent() 指向 root（对象引用完整）
}
```

**bug 历史**：`linkMessageToTree` 在 `messageRepository.save` 之前调用，导致邻接表指针（`parent_node_id`）没有被写入数据库。对象引用自然也不存在。详见第三章。

---

## 7. 两种模式的职责分工

| 场景 | 使用邻接表 | 使用对象引用 |
|------|-----------|------------|
| 从数据库加载一棵子树 | 递归 CTE 通过 `parent_node_id` 找到所有行 | `assembleTree` 将行列表转换为对象图 |
| 从根节点遍历到叶子 | 不需要 | `node.children()` 递归 |
| 从叶子节点找到根 | 反复查询 `parent_node_id`（慢） | `node.parent()` 链（O(1)） |
| 判断两个节点是否在同一个树中 | JOIN 或子查询 | `leaf.parent() != null` |
| 修改父节点 | UPDATE `parent_node_id` | `node.setParent(newParent)` |
| 持久化 | INSERT 行（含 `parent_node_id`） | `collectNodes()` 递归遍历对象图 |
| 恢复执行（中断后） | 递归 CTE 重新加载 | 重新执行 `assembleTree` |
| 跨进程/跨服务传递 | JSON 序列化（含 `parent_node_id`） | 需要反序列化后重建引用 |

**核心原则**：**持久化用邻接表，运行时用对象引用。** 两者的转换发生在 `ForestRepositoryAdapter` 的 `assembleTree()` 和 `collectNodes()` 中。
