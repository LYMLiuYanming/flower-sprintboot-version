package org.liuym.flowerv1springboot.common;

/**
 * 业务异常：由 Service 层抛出，GlobalExceptionHandler 统一转为 Result JSON
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }
}
