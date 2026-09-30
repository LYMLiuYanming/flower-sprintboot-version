package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.common.RichTextPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/**
 * 所有 JSON 请求体在进 Controller 之前统一过富文本白名单（L06）。
 *
 * <p>选 RequestBodyAdvice 而不是逐个 Service 加一行 clean：本批不能改他人 Service 文件，
 * 而「新增一个富文本字段就忘清洗」的风险恰恰来自分散。这里一个切点覆盖全部 @RequestBody，
 * 具体清哪些字段由 {@link RichTextPolicy} 的清单决定，两边都不需要为新增接口改代码。
 *
 * <p>不在覆盖范围内的入口（已在报告里列为待接线）：表单 @RequestParam / @ModelAttribute 提交、
 * multipart 文件本身、以及后台直接拼 SQL 的导入脚本。
 */
@RestControllerAdvice
public class RichTextSanitizeAdvice extends RequestBodyAdviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RichTextSanitizeAdvice.class);

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 只有对象类请求体需要清洗；String/Map 之类交给下面的按字段名判定，代价是要多走一次反射
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (body == null) {
            return null;
        }
        try {
            return RichTextPolicy.sanitize(body);
        } catch (RuntimeException e) {
            // 清洗属于加固能力，异常时放行原始对象：宁可少一道防护，也不把正常业务请求打死
            log.warn("富文本清洗异常 {} -> {}", parameter.getExecutable() == null ? "?"
                    : parameter.getExecutable().getName(), e.getClass().getSimpleName());
            return body;
        }
    }

    /** 空请求体不做任何事：@Valid 会给出「请求体缺失」的既有文案，不该在这里改成空对象 */
    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }
}
