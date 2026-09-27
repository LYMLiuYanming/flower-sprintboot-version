package org.liuym.flowerv1springboot.security;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * 图形验证码：注册等易被脚本批量调用的接口使用，答案仅存服务端，5 分钟内一次性有效
 */
@Component
public class CaptchaService {

    public record Challenge(String captchaId, String imageBase64, int expireSeconds) {
    }

    private static final int EXPIRE_SECONDS = 300;

    private final Cache<String, String> store = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(EXPIRE_SECONDS))
            .maximumSize(50_000)
            .build();

    public Challenge issue() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, captcha.getCode().toLowerCase(Locale.ROOT));
        return new Challenge(id, captcha.getImageBase64Data(), EXPIRE_SECONDS);
    }

    /**
     * 校验后立即失效，避免同一验证码重复提交
     */
    public boolean verify(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String expected = store.getIfPresent(captchaId);
        store.invalidate(captchaId);
        return expected != null && expected.equals(input.trim().toLowerCase(Locale.ROOT));
    }
}
