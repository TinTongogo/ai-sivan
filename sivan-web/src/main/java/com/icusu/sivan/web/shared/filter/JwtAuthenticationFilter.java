package com.icusu.sivan.web.shared.filter;

import com.icusu.sivan.domain.account.IAccountRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * JWT 认证过滤器 — 验证 Bearer Token 签名后查询数据库确认账户存在且活跃，再注入 SecurityContext。
 */
public class JwtAuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/", "/public/", "/actuator/", "/v3/api-docs", "/swagger-ui"
    );

    private final SecretKey secretKey;
    private final ServerSecurityContextRepository securityContextRepository;
    private final IAccountRepository accountRepository;

    public JwtAuthenticationFilter(String jwtSecret, ServerSecurityContextRepository securityContextRepository,
                                   IAccountRepository accountRepository) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.securityContextRepository = securityContextRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }
        if (PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange);
        if (token == null) {
            log.debug("[JWT] {} — 无 Token，返回 401", path);
            return writeUnauthorized(exchange, "未登录或登录已过期，请重新登录");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID accountId = UUID.fromString(claims.getSubject());
            String role = claims.get("role", String.class);
            Integer jwtTver = claims.get("tver", Integer.class);
            String projectIdStr = claims.get("projectId", String.class);
            UUID projectId = projectIdStr != null ? UUID.fromString(projectIdStr) : null;

            return Mono.fromCallable(() -> accountRepository.findById(accountId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(accountOpt -> {
                        if (accountOpt.isEmpty() || !accountOpt.get().isActive()) {
                            log.warn("[JWT] {} — 账户不存在或已禁用: {}", path, accountId);
                            return writeUnauthorized(exchange, "账户不存在或已被禁用");
                        }

                        // token version 校验：改密码后旧版本 token 自动失效
                        if (jwtTver != null && jwtTver < accountOpt.get().getTokenVersion()) {
                            log.warn("[JWT] {} — Token 版本过期(需重新登录): accountId={}", path, accountId);
                            return writeUnauthorized(exchange, "密码已修改，请重新登录");
                        }

                        List<SimpleGrantedAuthority> authorities = List.of(
                                new SimpleGrantedAuthority("ROLE_" + (role != null ? role.toUpperCase() : "USER"))
                        );
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(accountId, null, authorities);

                        SecurityContext securityContext = new SecurityContextImpl(auth);

                        exchange.getAttributes().put("accountId", accountId);
                        if (projectId != null) {
                            exchange.getAttributes().put("projectId", projectId);
                        }
                        if (role != null) {
                            exchange.getAttributes().put("role", role);
                        }

                        return securityContextRepository.save(exchange, securityContext)
                                .then(chain.filter(exchange));
                    });
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] {} — Token 已过期", path);
            return writeUnauthorized(exchange, "登录已过期，请重新登录");
        } catch (io.jsonwebtoken.security.SecurityException |
                 io.jsonwebtoken.MalformedJwtException | io.jsonwebtoken.UnsupportedJwtException |
                 IllegalArgumentException e) {
            log.warn("[JWT] {} — Token 无效: {} {}", path, e.getClass().getSimpleName(), e.getMessage());
            return writeUnauthorized(exchange, "Token 无效，请重新登录");
        }
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private String extractToken(ServerWebExchange exchange) {
        String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
