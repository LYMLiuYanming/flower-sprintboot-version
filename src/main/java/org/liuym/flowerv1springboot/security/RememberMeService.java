package org.liuym.flowerv1springboot.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 「记住我」：签名 Cookie 实现的持久登录态，不额外建表。
 * Cookie 值 = base64url(userId:过期秒:密码指纹) + "." + HMAC-SHA256(签名)。
 * 密码指纹取当前 BCrypt 哈希的摘要，因此改密/管理员重置会自动作废所有旧令牌。
 */
@Service
public class RememberMeService {

    public static final String COOKIE_NAME = "FLOWER_REMEMBER";

    private static final Logger log = LoggerFactory.getLogger(RememberMeService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long maxAgeSeconds;

    public RememberMeService(@Value("${app.remember-me.secret:}") String configuredSecret,
                             @Value("${app.remember-me.days:14}") long days) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // 未配置密钥时按实例随机生成：功能可用，但重启后需重新登录
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
            log.warn("remember-me 未配置 app.remember-me.secret，使用随机密钥（应用重启后 Cookie 失效）");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.maxAgeSeconds = Math.max(1, days) * 86400L;
    }

    /**
     * 登录成功且勾选「记住我」时下发持久 Cookie
     */
    public void issue(HttpServletResponse response, User user) {
        long expiresAt = Instant.now().getEpochSecond() + maxAgeSeconds;
        String payload = user.getId() + ":" + expiresAt + ":" + passwordFingerprint(user.getPassword());
        String value = base64UrlEncode(payload) + "." + sign(payload);

        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge((int) maxAgeSeconds);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /**
     * 退出登录立即清除 Cookie，避免残留令牌
     */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() == null) {
            return;
        }
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    /**
     * 解析并校验 Cookie；签名或过期不合法返回空，由调用方再比对用户状态与密码指纹
     */
    public Optional<Token> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie cookie : request.getCookies()) {
            if (!COOKIE_NAME.equals(cookie.getName()) || cookie.getValue() == null || cookie.getValue().isBlank()) {
                continue;
            }
            int dot = cookie.getValue().lastIndexOf('.');
            if (dot <= 0 || dot == cookie.getValue().length() - 1) {
                return Optional.empty();
            }
            String payload = tryDecode(cookie.getValue().substring(0, dot));
            if (payload == null || !signatureMatches(cookie.getValue().substring(dot + 1), payload)) {
                return Optional.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                UUID userId = UUID.fromString(parts[0]);
                long expiresAt = Long.parseLong(parts[1]);
                if (expiresAt < Instant.now().getEpochSecond()) {
                    return Optional.empty();
                }
                return Optional.of(new Token(userId, parts[2]));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 令牌里的密码指纹是否与当前哈希一致（改密即作废）
     */
    public boolean matchesPassword(Token token, String storedPassword) {
        return MessageDigest.isEqual(token.passwordFingerprint().getBytes(StandardCharsets.UTF_8),
                passwordFingerprint(storedPassword).getBytes(StandardCharsets.UTF_8));
    }

    public record Token(UUID userId, String passwordFingerprint) {
    }

    private String passwordFingerprint(String storedPassword) {
        if (storedPassword == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(storedPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return base64UrlEncode(new String(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 remember-me 签名", e);
        }
    }

    private boolean signatureMatches(String provided, String payload) {
        return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String tryDecode(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
