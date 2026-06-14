# ADR-024：OAuth 授权流程增加 PKCE 要求

> **状态**：✅ 已决策
> **日期**：2026-06-06
> **作者**：交叉评审 → 系统架构组

---

## 背景

交叉评审发现 **吴-R3 关注点**：`startConfig()` 中提到的 OAuth 流程未使用 PKCE 扩展。特别是在移动端或手表端场景中，没有 PKCE 的 OAuth 授权流程存在授权码拦截风险。

## 决策

所有 OAuth 授权流程必须使用 PKCE（Proof Key for Code Exchange）：

| 要素 | 要求 |
|------|------|
| 授权请求 | 必须携带 `code_challenge`（S256 算法） |
| 令牌交换 | 必须携带 `code_verifier` |
| 适用场景 | 所有 OAuth 流程（Web / 移动端 / 手表端） |

### 影响

- `McpDiscoveryService.startConfig()` 中触发的 OAuth 流程需实现 PKCE
- 不依赖第三方 OAuth 库的具体实现——PKCE 已是 OAuth 2.0 安全基线

## 理由

1. 移动端和手表端场景无浏览器地址栏可见，授权码拦截攻击的风险更高。
2. PKCE 已是 OAuth 2.1 的强制性要求，OAuth 2.0 也强烈推荐。
3. PKCE 只需要在客户端增加两行参数（`code_challenge` + `code_verifier`），实现成本极低。

## 参考文献

- [review-最终交叉评审报告.md](../20-评审/review-最终交叉评审报告.md) 吴-R3
- [07-工具动态感知.md](../10-设计/07-工具动态感知.md) §4.6
- [RFC 7636: Proof Key for Code Exchange](https://datatracker.ietf.org/doc/html/rfc7636)
