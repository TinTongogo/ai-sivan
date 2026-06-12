package com.icusu.sivan.web.shared.handler;

import com.icusu.sivan.common.dto.ApiError;
import com.icusu.sivan.common.exception.DomainException;
import com.icusu.sivan.common.exception.ResourceNotFoundException;
import com.icusu.sivan.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一返回 ApiError 格式（08-API契约 §4.4）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 解析异常消息。 */
    private String resolveMessage(DomainException ex) {
        if (ex.getMessageCode() != null) {
            Locale locale = LocaleContextHolder.getLocale();
            return messageSource.getMessage(ex.getMessageCode(), ex.getMessageArgs(), ex.getMessage(), locale);
        }
        return ex.getMessage();
    }

    /** 构建 ApiError。 */
    private ApiError error(int code, String message, String detail, ServerWebExchange exchange) {
        String path = exchange != null ? exchange.getRequest().getURI().getPath() : "";
        return new ApiError(code, message, detail, path, Instant.now());
    }

    /** 处理资源未找到异常，返回 404。 */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ApiError.GOAL_NOT_FOUND, message, null, exchange));
    }

    /** 处理未授权异常，返回 401。 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        log.warn("[401] UnauthorizedException: {}", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(401, message, null, exchange));
    }

    /** 处理领域异常，根据错误码映射 HTTP 状态码。 */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        log.warn("[{}] DomainException: {}", ex.getCode(), message);
        int code = switch (ex.getCode()) {
            case 400 -> ApiError.VALIDATION_ERROR;
            case 403 -> ApiError.POLICY_VIOLATION;
            case 404 -> ApiError.GOAL_NOT_FOUND;
            case 409 -> ApiError.VALIDATION_ERROR;
            default -> ApiError.INTERNAL_ERROR;
        };
        HttpStatus status = switch (ex.getCode()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(error(code, message, null, exchange));
    }

    /** 处理参数校验异常，返回 400 及校验错误详情。 */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ApiError.VALIDATION_ERROR, "参数校验失败", detail, exchange));
    }

    /** 处理 HTTP 方法不支持异常。 */
    @ExceptionHandler(MethodNotAllowedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(MethodNotAllowedException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error(ApiError.VALIDATION_ERROR, ex.getMessage(), null, exchange));
    }

    /** 处理不支持的 Content-Type 异常。 */
    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(UnsupportedMediaTypeStatusException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error(ApiError.VALIDATION_ERROR, ex.getMessage(), null, exchange));
    }

    /** 处理非法参数异常，返回 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ApiError.VALIDATION_ERROR, ex.getMessage(), null, exchange));
    }

    /** 处理数据完整性冲突异常，返回 409。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, ServerWebExchange exchange) {
        log.error("数据完整性冲突", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ApiError.VALIDATION_ERROR, "操作失败：数据冲突，请稍后重试", null, exchange));
    }

    /** 处理未预期异常，返回 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, ServerWebExchange exchange) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(ApiError.INTERNAL_ERROR, "服务器内部错误", null, exchange));
    }
}
