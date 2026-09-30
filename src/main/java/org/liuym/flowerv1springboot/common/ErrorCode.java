package org.liuym.flowerv1springboot.common;

/**
 * 错误码与用户可见文案的集中入口（L13）。
 *
 * <p>不做大爆炸式替换：既有 controller 里的字符串照常工作，这里是**新增文案的唯一落点**，
 * 老文案按接口逐个迁。迁移优先级写在每个分节的注释里，方便主智能体按点收。
 *
 * <p>约定（与 {@link Result} 一致）：
 * <ul>
 *   <li>200 成功；400 参数/业务规则；401 未登录；403 无权限；404 不存在；</li>
 *   <li>409 状态冲突（重复提交、非法跃迁、凭证失效）；413 上传超限；429 频率超限；500 兜底；</li>
 *   <li>503 依赖不可用（外部服务熔断、存储目录不可写）。</li>
 * </ul>
 *
 * <p>429/413/503 之前散落在拦截器与控制器里写成裸数字，这里定型后再无第二处。
 */
public enum ErrorCode {

    /* ---- 鉴权与账号 ---- */
    UNAUTHORIZED(401, "请先登录"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_LOGIN_ADMIN(403, "无管理员权限"),

    /* ---- 参数与业务规则 ---- */
    BAD_REQUEST(400, "请求参数不正确"),
    NOT_FOUND(404, "内容不存在或已下架"),
    CONFLICT(409, "操作未能完成，请刷新后重试"),

    /* ---- 防重复提交（L03） ---- */
    TOKEN_MISSING(409, "缺少防重复凭证，请刷新页面后重新提交"),
    TOKEN_EXPIRED(409, "凭证已失效，请刷新页面后重新提交"),
    TOKEN_REPLAY(409, "这次提交已经处理过了，请勿重复操作"),

    /* ---- 频率（L04） ---- */
    TOO_MANY_REQUESTS(429, "操作太频繁了，请稍后再试"),
    LOGIN_TOO_MANY(429, "登录尝试过多，请稍后再试或换个网络"),
    CLAIM_TOO_MANY(429, "领券太频繁，请几分钟后再来"),
    ORDER_TOO_MANY(429, "下单太频繁，请确认没有重复提交"),
    UPLOAD_TOO_MANY(429, "上传太频繁，请稍后再试"),

    /* ---- 上传（L05） ---- */
    UPLOAD_EMPTY(400, "请选择要上传的文件"),
    UPLOAD_TOO_LARGE(413, "文件超出大小限制"),
    UPLOAD_TYPE_UNSUPPORTED(400, "文件类型不支持，仅接受图片"),
    UPLOAD_STORAGE_UNAVAILABLE(503, "图片存储目录暂不可用，请联系管理员"),

    /* ---- 外部依赖（L07） ---- */
    AMAP_UNAVAILABLE(503, "地图服务暂时不可用，已改用本地估算"),

    /* ---- 系统兜底 ---- */
    INTERNAL_ERROR(500, "服务开小差了，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /* ---- 三个常用出口：Service 抛异常、Controller 直接回 Result ---- */

    /** 用本码的默认文案抛业务异常 */
    public BusinessException ex() {
        return new BusinessException(code, message);
    }

    /** 换掉文案但保留码：同一类失败在不同场景要说不同的话（如「券已抢完」与「券已过期」都是 409） */
    public BusinessException ex(String customMessage) {
        return new BusinessException(code, customMessage);
    }

    public <T> Result<T> result() {
        return Result.error(code, message);
    }

    public <T> Result<T> result(String customMessage) {
        return Result.error(code, customMessage);
    }

    /**
     * 待迁移点（按撞车风险从低到高）：
     * <ol>
     *   <li>{@code IdempotencyServiceImpl} 的私有常量 CONFLICT=409 与三句凭证文案 → {@link #TOKEN_MISSING}
     *       / {@link #TOKEN_EXPIRED} / {@link #TOKEN_REPLAY}；</li>
     *   <li>{@code UploadController} 的四句校验文案 → {@link #UPLOAD_EMPTY} / {@link #UPLOAD_TOO_LARGE}
     *       / {@link #UPLOAD_TYPE_UNSUPPORTED} / {@link #UPLOAD_STORAGE_UNAVAILABLE}；</li>
     *   <li>{@code AuthInterceptor#writeJson} 的「请先登录」「无管理员权限」→ {@link #UNAUTHORIZED}
     *       / {@link #NOT_LOGIN_ADMIN}；</li>
     *   <li>{@code GlobalExceptionHandler} 的兜底文案与 {@link #BAD_REQUEST}、{@link #INTERNAL_ERROR} 对齐。</li>
     * </ol>
     * 这些都是他人/跨组文件，本批不批量改，避免与并行批次抢同一行。
     */
    public static ErrorCode of(int code) {
        for (ErrorCode item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return INTERNAL_ERROR;
    }
}
