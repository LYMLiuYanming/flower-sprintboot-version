package org.liuym.flowerv1springboot.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一 API 响应体
 * code 约定：200 成功；401 未登录；403 无权限；404 资源不存在；500 服务端错误
 * 注意：前端页面按 body 中的 code 字段判断业务状态，改造接口时保持该约定不变
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private Integer code;
    private String msg;
    private T data;

    /** 分页总数（仅列表类接口返回，兼容 layui.table 协议） */
    private Long count;

    /** 额外字段（如 itemCount），序列化时展平到顶层，保持旧接口契约不变 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> extras;

    /**
     * 链式追加顶层字段，用于兼容历史接口的非标准字段
     */
    public Result<T> with(String key, Object value) {
        if (extras == null) {
            extras = new HashMap<>();
        }
        extras.put(key, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtras() {
        return extras == null ? Map.of() : extras;
    }

    public Result() {
    }

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 分页列表结果：data 为当页数据，count 为总条数（layui.table 协议）
     */
    public static <T> Result<T> page(T data, long total) {
        Result<T> r = new Result<>(200, "success", data);
        r.count = total;
        return r;
    }

    public static <T> Result<T> ok(String msg, T data) {
        return new Result<>(200, msg, data);
    }

    public static <T> Result<T> unauthorized() {
        return new Result<>(401, "请先登录", null);
    }

    public static <T> Result<T> forbidden(String msg) {
        return new Result<>(403, msg, null);
    }

    public static <T> Result<T> notFound(String msg) {
        return new Result<>(404, msg, null);
    }

    public static <T> Result<T> error(String msg) {
        return new Result<>(500, msg, null);
    }

    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    /**
     * 是否成功（供调用方判断，不参与 JSON 序列化）
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code != null && code == 200;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
