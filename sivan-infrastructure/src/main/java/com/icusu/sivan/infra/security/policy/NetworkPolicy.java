package com.icusu.sivan.infra.security.policy;

import com.icusu.sivan.common.util.UrlValidator;
import com.icusu.sivan.common.util.UrlValidator.ValidationResult;
import com.icusu.sivan.domain.security.*;
import org.springframework.stereotype.Component;

/**
 * 网络策略 — 通过 UrlValidator 校验 HTTP 请求。
 * <p>
 * 校验规则：
 * <ul>
 *   <li>协议白名单（仅 http/https）</li>
 *   <li>SSRF 防护（DNS 重绑定 + 私有地址检测）</li>
 *   <li>内嵌凭证检测</li>
 * </ul>
 */
@Component
public class NetworkPolicy implements Policy<Action> {

    @Override
    public void validate(Action action, SecurityContext ctx) {
        if (!(action instanceof HttpRequest http)) {
            throw new PolicyViolationException("不支持的 HTTP 操作: " + action.getClass().getSimpleName());
        }
        com.icusu.sivan.common.util.UrlValidator.ValidationResult result = UrlValidator.validate(http.url());
        if (!result.valid()) {
            throw new PolicyViolationException("URL 校验失败: " + result.errorMessage());
        }
    }

    @Override
    public Class<Action> actionType() {
        return Action.class;
    }

    @Override
    public String requiredPermission() {
        return "network";
    }
}
