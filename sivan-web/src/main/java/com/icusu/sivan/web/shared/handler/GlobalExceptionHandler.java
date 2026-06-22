package com.icusu.sivan.web.shared.handler;

import com.icusu.sivan.common.dto.BaseResponse;
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

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，统一返回 BaseResponse 格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 解析异常消息。
     */
    private String resolveMessage(DomainException ex) {
        if (ex.getMessageCode() != null) {
            Locale locale = LocaleContextHolder.getLocale();
            return messageSource.getMessage(ex.getMessageCode(), ex.getMessageArgs(), ex.getMessage(), locale);
        }
        return ex.getMessage();
    }

    /**
     * 处理资源未找到异常，返回 404。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleNotFound(ResourceNotFoundException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.internalError(message));
    }

    /**
     * 处理未授权异常，返回 401。
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<BaseResponse<Void>> handleUnauthorized(UnauthorizedException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        log.warn("[401] UnauthorizedException: {}", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(BaseResponse.unauthorized(message));
    }

    /**
     * 处理领域异常，根据错误码映射 HTTP 状态码。
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<BaseResponse<Void>> handleDomain(DomainException ex, ServerWebExchange exchange) {
        String message = resolveMessage(ex);
        log.warn("[{}] DomainException: {}", ex.getCode(), message);
        HttpStatus status = switch (ex.getCode()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(BaseResponse.internalError(message));
    }

    /**
     * 处理参数校验异常，返回 400 及校验错误详情。
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<BaseResponse<Void>> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.internalError("参数校验失败: " + detail));
    }

    /**
     * 处理 HTTP 方法不支持异常。
     */
    @ExceptionHandler(MethodNotAllowedException.class)
    public ResponseEntity<BaseResponse<Void>> handleMethodNotAllowed(MethodNotAllowedException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(BaseResponse.internalError(ex.getMessage()));
    }

    /**
     * 处理不支持的 Content-Type 异常。
     */
    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public ResponseEntity<BaseResponse<Void>> handleUnsupportedMediaType(UnsupportedMediaTypeStatusException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(BaseResponse.internalError(ex.getMessage()));
    }

    /**
     * 处理非法参数异常，返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResponse<Void>> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.internalError(ex.getMessage()));
    }

    /**
     * 处理数据完整性冲突异常，返回 409。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<BaseResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex, ServerWebExchange exchange) {
        log.error("数据完整性冲突", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BaseResponse.internalError("操作失败：数据冲突，请稍后重试"));
    }

    /**
     * 处理未预期异常，返回 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleUnknown(Exception ex, ServerWebExchange exchange) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.internalError("服务器内部错误"));
    }
}
