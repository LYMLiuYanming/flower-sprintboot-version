package org.liuym.flowerv1springboot.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 下单凭证的签发与有效期判定：随机串只保证不可猜，唯一性由数据库唯一约束兜底。
 */
public final class IdempotencyPolicy {

    /** 凭证有效期：结算页停留时间远小于此值，过期只做兜底清理 */
    public static final int TTL_MINUTES = 120;
    private static final int TOKEN_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdempotencyPolicy() {
    }

    public static String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 过期凭证不再接受，避免把结算页缓存了几个月的 token 换成一笔真订单 */
    public static boolean expired(LocalDateTime createdAt, LocalDateTime now) {
        return createdAt == null || createdAt.plusMinutes(TTL_MINUTES).isBefore(now);
    }
}
