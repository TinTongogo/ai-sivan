# ADR-003：ForestVisitor 改用经典 accept 模式

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：第三方终审 → 系统架构组

---

## 背景

在终审阶段，第三方架构师林工对森林架构的 Visitor 设计提出 **C1 关注点**（详见 `review-第三方终审报告.md §1.3`）：

设计文档中 `ExecuteVisitor` / `CompressVisitor` 使用 `instanceof` 做运行时类型判断：

```java
// 当前设计（运行时 instanceof）
class ExecuteVisitor implements ForestVisitor {
    boolean enter(ForestNode node) {
        if (node instanceof ExecutableNode execNode) {
            return dispatch(execNode);
        }
        return false;
    }
}
```

`instanceof` 本身不是问题，但当节点类型从当前的 5 种增长到 10+ 种时，每个 Visitor 实现都需要维护一组 `instanceof` 分支，漏掉新类型只会在运行时暴露。

## 可选方案

### 方案 A：保留 instanceof（原始设计）

Visitor 实现中使用模式匹配的 `instanceof` 做运行时类型分派。

**优点**：
- 改动最小，无需新增接口方法
- Java 21+ 的模式匹配 `instanceof` 语法已是语言特性

**缺点**：
- 新增节点类型后，所有 Visitor 实现需要同步更新 `instanceof` 链
- 漏掉某个节点类型只在运行时暴露（测试覆盖不到就上线）
- 违反 LSP 的显式规则——"不能出现 `if (node instanceof TaskNode)` 的特判"

### 方案 B：经典 accept 模式（建议方案）

在 `TreeNode` 接口上增加 `accept(ForestVisitor)` 方法，`ForestVisitor` 定义为 typed visit 方法：

```java
interface ForestVisitor {
    void visitInnerGoal(InnerGoalNode node);
    void visitTask(TaskNode node);
    void visitSynthesis(SynthesisNode node);
    void visitMessage(MessageNode node);
    void visitMemory(MemoryNode node);
}

interface TreeNode {
    // ... 原有方法 ...
    void accept(ForestVisitor visitor);
}

class TaskNode implements ExecutableNode, CompressibleNode, ContentNode {
    void accept(ForestVisitor v) { v.visitTask(this); }
}
```

**优点**：
- 编译期检查——新增节点类型后，`ForestVisitor` 接口增加一个 `visit*` 方法，所有实现类会编译报错，不会漏掉
- 与经典 GOF Visitor 模式一致，后续维护心智负担低
- Visitor 实现中零 `instanceof`，无需维护运行时类型判断链
- 接口本身就是节点类型清单的活文档

**缺点**：
- 需要修改 `TreeNode` 接口（新增一个方法）
- 每个具体节点类需要实现一行 `accept()` 委派
- 新增节点类型时，`ForestVisitor` 接口本身也需要新增方法（对编译期安全的自然代价）

## 决策：采用方案 B（经典 accept 模式）

### 理由

1. **编译期安全 vs 运行时安全**。核心设计规则已经要求"零 `switch(nodeType)`，所有分派走 Registry"。`instanceof` 检查本质上等同 `switch` 的运行时分派。`accept` 模式将检查从运行时提前到编译期，与架构纪律一致。

2. **节点类型持续增长**。当前 5 种节点类型（InnerGoal / Task / Synthesis / Message / Memory），未来预期增加到 10+ 种（FileNode、ToolResultNode 等）。每增加一种，Visitor 的 `instanceof` 维护成本线性上升。

3. **零额外复杂度**。每节点一行 `accept()` 的 boilerplate 是经典 Visitor 模式的固定成本。作为回报，编译器替人类检查所有 Visitor 是否已覆盖新类型。

4. **enter/exit 遍历控制不变**。`accept` 只替代 `instanceof` 做类型分派，遍历控制（是否进入 children）仍在 `ForestVisitor` 的具体实现中管理（详见 `01-森林架构-编排与执行.md §10.5`）。

### 否决方案 A 的原因

保留 `instanceof` 在短期内可行（当前仅 5 种类型），但长期维护成本不可控。测试无法保证所有 Visitor 正确覆盖所有类型——总会有人漏写一个 `if (node instanceof NewType)`。

### 技术细节

#### ForestVisitor 接口定义

`ForestVisitor` 为每种节点类型定义一个 `visit*` 方法：

| 方法 | 参数类型 | 说明 |
|------|---------|------|
| `visitInnerGoal` | `InnerGoalNode` | 内部目标节点 |
| `visitTask` | `TaskNode` | 任务节点（叶子） |
| `visitSynthesis` | `SynthesisNode` | 合成节点 |
| `visitMessage` | `MessageNode` | 消息节点 |
| `visitMemory` | `MemoryNode` | 记忆节点 |

#### TreeNode 的 accept 方法

```java
interface TreeNode {
    void accept(ForestVisitor visitor);
    // ... 原有方法 ...
}
```

#### 各节点类的 accept 委派

每个节点类一行实现，不依赖任何框架或工具：

```java
class TaskNode implements ExecutableNode, CompressibleNode, ContentNode {
    @Override
    public void accept(ForestVisitor v) { v.visitTask(this); }
}
```

Visitor 实现不再出现 `instanceof`，新节点类型由编译器保证覆盖。

### 未解决的问题

- 当前实际遍历由 `ForestExecutor.executeNode()` 直接递归完成，未使用 `ForestVisitor`。`accept` 模式是前瞻性设计约定，Phase 0 不强制要求遍历路径改为 Visitor 模式。
- 如果未来引入 `ForestVisitor` 做实际遍历，需补充 `enter(return skipChildren)` / `exit` 的遍历控制约定。

---

## 参考文献

- [review-第三方终审报告.md](../20-评审/review-第三方终审报告.md) §1.3 C1 关注点
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §10.5 ISP — 更新后的伪代码
- [01-森林架构-编排与执行.md](../10-设计/01-森林架构-编排与执行.md) §10.3 LSP — 编译期保障规则
- Gamma et al., *Design Patterns: Elements of Reusable Object-Oriented Software*, Visitor (p. 331)
