# ADR-001：JDK 版本选型 — JDK 25 LTS

> **状态**：✅ 已决策
> **日期**：2026-06-05
> **作者**：系统架构组

---

## 背景

实现计划 `plan-实现计划.md` 原始指定 **JDK 21 + Spring Boot 3.4.5**。但在项目启动前，JDK 25 已于 2025 年 9 月发布并被 Oracle 指定为 **LTS 版本**（8 年商业支持至 2033 年）。需要重新评估版本选型。

## 可选方案

### 方案 A：JDK 21（原始计划）

| 属性 | 值 |
|------|-----|
| 版本 | 21.0.9 LTS |
| 发布时间 | 2023-09 |
| 支持截止 | 2031-09（Oracle） |
| 关键特性 | Virtual Threads GA、Record Pattern、Pattern Matching for switch |

### 方案 B：JDK 25（新选型）

| 属性 | 值 |
|------|-----|
| 版本 | 25.0.1 LTS |
| 发布时间 | 2025-09 |
| 支持截止 | 2033-09（Oracle，8 年） |
| 关键特性 | 见下文 |

---

## JDK 25 对 Sivan 有意义的特性

### P0 — 直接影响 Phase 0 验收标准

**Compact Object Headers（JEP 519）** ✅ 最终特性

对象头从 12-16 字节缩小到 8 字节（64 位），堆内存减少 **10-20%**。

> Phase 0 验收标准："1000 节点树内存增量 < 5MB"。每个 `TreeNode`/`ExecutionContext` 都是对象，Compact Headers 白送 10%+ 内存裕量，直接降低压测风险。

### P1 — 架构级相关

**Scoped Values（JEP 506）** ✅ 最终特性

不可变、可跨线程继承的上下文容器，是 ThreadLocal 的**安全替代品**。Sivan 架构明令禁止 ThreadLocal，Scoped Values 不同：
- 不可变（写后不能改，杜绝脏数据传递）
- 子线程自动继承（Reactor 原生支持 `Hooks.enableAutomaticContextPropagation()`）
- 适合放 **infrastructure 层**横切信息（traceId、请求来源 IP）

> 不违反"accountId 显式传递"原则 — accountId 依然是领域层显式参数，Scoped Values 用于基础设施层。

**Generational Shenandoah GC（JEP 521）** ✅ 最终特性

生产级分代 Shenandoah，对长时 AI 会话更友好 — 低延迟 GC 配合 Reactor 异步非阻塞模型，减少 STW 停顿。

**Flexible Constructor Bodies（JEP 513）** ✅ 最终特性

构造器中可以在 `super()`/`this()` 之前写校验逻辑。对领域实体有用：

```java
// JDK 25
public InnerGoalNode {
    if (children == null || children.isEmpty()) 
        throw new IllegalArgumentException();
    // super() 自动调用
}
```

### P2 — 长期受益

| 特性 | 状态 | 对 Sivan 的价值 |
|------|------|----------------|
| **AOT Method Profiling（JEP 515）** | ✅ 最终 | Dev 迭代快 15-25% 启动速度 |
| **Module Import Declarations（JEP 511）** | ✅ 最终 | `import module java.base;` 简化导入 |
| **Structured Concurrency（JEP 505）** | 🔄 5th Preview | 概念上契合森林层次化并发，待最终化 |
| **Primitive Types in Patterns（JEP 507）** | 🔄 3rd Preview | mode 类型枚举模式匹配更简洁 |

---

## 决策：选择 JDK 25

### 理由

1. **JDK 25 是 LTS** — Oracle 提供 8 年支持至 2033 年，比 JDK 21 长 2 年
2. **Compact Object Headers 直接影响 Phase 0** — 1000 节点树 < 5MB 的验收标准有了额外裕量
3. **Scoped Values 对齐架构纪律** — 比 ThreadLocal 安全，适合基础设施层横切关注点
4. **无迁移成本** — 新项目，没有旧代码需要适配
5. **Spring Boot 4.0.x 已原生支持** — 见 [ADR-002](40-决策/ADR-002-SpringBoot4-选型.md)

### 代价

- 编译目标改为 JDK 25（与运行时一致），比原计划的 `--release 21` 更高
- 所有测试在 JDK 25 下通过（含 1000 节点压测 + 111 节点真实 LLM 执行）
- 部分 JDK 25 preview 特性（Structured Concurrency、Primitive Patterns）暂不使用，待最终化

### 技术细节

```xml
<!-- pom.xml -->
<properties>
    <java.version>25</java.version>              <!-- 编译目标 + 运行时一致 -->
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>
```

```properties
# .mvn/jvm.config
-Xmx512m
```

> 运行时 JDK 25 的 Compact Object Headers 和 Scoped Values 无需特殊配置 — 开箱即用。

---

## 参考文献

- [OpenJDK JDK 25 Release Notes](https://openjdk.org/projects/jdk/25/)
- [Performance Improvements in JDK 25](https://inside.java/2025/10/20/jdk-25-performance-improvements/)
- [The three game-changing features of JDK 25](https://www.infoworld.com/article/4057212/the-three-game-changing-features-of-jdk-25.html)
- [Compact Object Headers JEP 519](https://openjdk.org/jeps/519)
- [Scoped Values JEP 506](https://openjdk.org/jeps/506)
