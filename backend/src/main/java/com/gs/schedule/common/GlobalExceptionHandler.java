package com.gs.schedule.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（含 409 冲突、乐观锁冲突）。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Object>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(R.fail(ex.getCode(), ex.getMessage(), ex.getPayload()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Object>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getField() + ": " + fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(R.fail(4001, msg, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Object>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(500).body(R.fail(5000, "服务内部错误: " + ex.getMessage(), null));
    }
}
