package com.gs.schedule.common;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 业务异常。conflict=true 时以 HTTP 409 返回，payload 携带冲突对象与时间段。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int httpStatus;
    private final int code;
    private final transient Object payload;

    public BusinessException(String message) {
        this(400, 4000, message, null);
    }

    public BusinessException(int httpStatus, int code, String message, Object payload) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.payload = payload;
    }

    /** 构造冲突异常（409），payload 即返回给前端的冲突明细。 */
    public static BusinessException conflict(String message, List<Map<String, Object>> conflicts) {
        return new BusinessException(409, 4090, message, Map.of("conflicts", conflicts));
    }
}
