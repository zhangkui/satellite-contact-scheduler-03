package com.gs.schedule.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一响应结构。冲突场景 HTTP 状态码使用 409，data 中携带冲突对象与时间段。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {
    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String message, T data) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.data = data;
        return r;
    }
}
