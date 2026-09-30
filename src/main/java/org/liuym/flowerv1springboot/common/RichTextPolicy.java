package org.liuym.flowerv1springboot.common;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 富文本白名单的集中清单（L06）：规定「哪些字段按富文本清洗、哪些按纯文本处理」，
 * 并把 {@link HtmlSanitizer} 的应用从「每个 Service 记着调一下」变成「入库前必然经过」。
 *
 * <p>为什么不逐字段写在 DTO 注解上：新增字段忘标的代价是一段可执行 HTML 进库，
 * 而集中清单忘标的代价只是这一个字段没被清洗——同时集中清单本身就是评审对象。
 *
 * <p>两条口径：
 * <ul>
 *   <li>{@link Bucket#RICH}：走 {@link HtmlSanitizer#clean}，白名单标签保留，其余转义；</li>
 *   <li>{@link Bucket#PLAIN}：走 {@link HtmlSanitizer#text}，标签全部剥离成纯文本（标题、花语这类不该有标签）。</li>
 * </ul>
 * 空值一律原样保留：清洗不能把「用户没填」变成「用户填了空串」，
 * 否则「选填字段被清空」这类语义就丢了。
 */
public final class RichTextPolicy {

    /** 清洗口径 */
    public enum Bucket {
        RICH,
        PLAIN
    }

    /**
     * 字段名 → 口径。名字用 DTO/实体的属性名（另有大小写不敏感兜底），
     * 新增可输入富文本字段往这里加，不要散到各个 Service。
     */
    private static final Map<String, Bucket> FIELDS = new LinkedHashMap<>();

    static {
        rich("content", "body", "html", "richText", "articleContent", "noticeContent", "summaryContent",
                "careTips", "careGuide", "careInfo", "maintenanceTips", "description", "detail", "details",
                "introduction", "intro", "summary", "abstractText", "excerpt", "reply", "replyContent",
                "appendContent", "answer", "question", "announcement", "guide", "tips", "story",
                "storeIntro", "originIntro", "categoryDescription", "seoDescription", "plainContent");
        plain("title", "name", "productName", "articleTitle", "headline", "subject", "flowerLanguage",
                "flowerSpeech", "suitableFor", "scene", "tag", "keywords", "label",
                "receiverName", "contactName", "company", "nickname", "fullName");
    }

    private static void rich(String... names) {
        for (String name : names) {
            FIELDS.put(name, Bucket.RICH);
        }
    }

    private static void plain(String... names) {
        for (String name : names) {
            FIELDS.put(name, Bucket.PLAIN);
        }
    }

    private RichTextPolicy() {
    }

    /** 在册清单（只读副本），后台诊断页与代码评审用它核对覆盖 */
    public static Map<String, Bucket> catalog() {
        return Map.copyOf(FIELDS);
    }

    public static int managedFields() {
        return FIELDS.size();
    }

    /**
     * 该字段名走哪种口径；不在册返回 null 表示「原样不动」。
     * 口令类字段永不参与清洗——把 password 里的 &lt; 转义掉会造成「密码错了」这种查半天的鬼故事。
     */
    public static Bucket bucketOf(String fieldName) {
        if (fieldName == null || Masking.isSecretKey(fieldName)) {
            return null;
        }
        Bucket exact = FIELDS.get(fieldName);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Bucket> entry : FIELDS.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(fieldName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 单值清洗：给 Service 直接调用，语义与请求体扫描完全一致 */
    public static String clean(String value, Bucket bucket) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return bucket == Bucket.PLAIN ? HtmlSanitizer.text(value) : HtmlSanitizer.clean(value);
    }

    /**
     * 递归清洗对象树，返回处理后的对象（可能是原实例，也可能是重建出来的 record 实例）。
     *
     * <p>record 不可变，只能走规范构造器重建；普通对象直接改字段。
     * visited 用 identity 语义记录路径上的节点，防止自引用结构（parent↔child）把栈打满。
     */
    public static Object sanitize(Object root) {
        return walk(root, "$", Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object walk(Object value, String fieldName, Set<Object> visited) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            Bucket bucket = bucketOf(fieldName);
            return bucket == null ? text : clean(text, bucket);
        }
        Class<?> type = value.getClass();
        if (isLeaf(type)) {
            return value;
        }
        if (!visited.add(value)) {
            return value;
        }
        try {
            if (value instanceof Collection<?> collection) {
                return walkCollection(collection, visited);
            }
            if (value instanceof Map<?, ?> map) {
                return walkMap(map, visited);
            }
            if (type.isArray()) {
                return walkArray(value, visited);
            }
            if (type.isRecord()) {
                return walkRecord(value, visited);
            }
            walkBean(value, visited);
            return value;
        } finally {
            visited.remove(value);
        }
    }

    /** 不可能带富文本、也不该往下钻的类型：JDK 自带值类型、第三方框架对象 */
    private static boolean isLeaf(Class<?> type) {
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return false;
        }
        return type.isEnum()
                || type.isPrimitive()
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || UUID.class.isAssignableFrom(type)
                || Class.class.isAssignableFrom(type)
                || type.getName().startsWith("java.")
                || type.getName().startsWith("javax.")
                || type.getName().startsWith("jakarta.")
                || type.getName().startsWith("org.springframework.");
    }

    private static Object walkRecord(Object value, Set<Object> visited) {
        RecordComponent[] components = value.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean changed = false;
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Object original = components[i].getAccessor().invoke(value);
                Object cleaned = walk(original, components[i].getName(), visited);
                if (cleaned != original) {
                    changed = true;
                }
                args[i] = cleaned;
            }
            if (!changed) {
                return value;
            }
            Constructor<?> constructor = value.getClass().getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 重建不了就原样放行：清洗失败不该让接口 500，漏网的清洗由 Service 层的显式调用兜底
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object walkCollection(Collection<?> source, Set<Object> visited) {
        List<Object> cleaned = new ArrayList<>(source.size());
        boolean changed = false;
        for (Object item : source) {
            // 集合里的字符串拿不到字段名，按富文本站位处理（宁可多清洗也别漏）
            Object next = walk(item, item instanceof String ? "content" : "$", visited);
            if (next != item) {
                changed = true;
            }
            cleaned.add(next);
        }
        if (!changed) {
            return source;
        }
        Collection<Object> raw = (Collection<Object>) source;
        raw.clear();
        raw.addAll(cleaned);
        return source;
    }

    @SuppressWarnings("unchecked")
    private static Object walkMap(Map<?, ?> source, Set<Object> visited) {
        Map<Object, Object> raw = (Map<Object, Object>) source;
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            Object key = entry.getKey();
            Object cleaned = walk(entry.getValue(), key instanceof String name ? name : "$", visited);
            if (cleaned != entry.getValue()) {
                entry.setValue(cleaned);
            }
        }
        return source;
    }

    private static Object walkArray(Object array, Set<Object> visited) {
        Class<?> component = array.getClass().getComponentType();
        int length = Array.getLength(array);
        if (component == null || component.isPrimitive() || isLeaf(component) || length == 0) {
            return array;
        }
        Object rebuilt = Array.newInstance(component, length);
        boolean changed = false;
        for (int i = 0; i < length; i++) {
            Object original = Array.get(array, i);
            Object cleaned = walk(original, "$", visited);
            if (cleaned != original) {
                changed = true;
            }
            if (component.isInstance(cleaned) || cleaned == null) {
                Array.set(rebuilt, i, cleaned);
            } else {
                // 元素类型对不上（极少见：List 元素被换成别的类型）就退回原数组
                return array;
            }
        }
        return changed ? rebuilt : array;
    }

    private static void walkBean(Object target, Set<Object> visited) {
        for (Class<?> cursor = target.getClass(); cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object original = field.get(target);
                    if (original == null || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }
                    Object cleaned = walk(original, field.getName(), visited);
                    if (cleaned != original) {
                        field.set(target, cleaned);
                    }
                } catch (ReflectiveOperationException | RuntimeException | Error e) {
                    // 单个字段读不到就跳过，一次清洗不能把整个请求打死
                }
            }
        }
    }

    /** 自检：本批在册字段里有多少走富文本、多少走纯文本 */
    public static Map<String, Integer> bucketCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("rich", (int) FIELDS.values().stream().filter(Bucket.RICH::equals).count());
        counts.put("plain", (int) FIELDS.values().stream().filter(Bucket.PLAIN::equals).count());
        return counts;
    }
}
