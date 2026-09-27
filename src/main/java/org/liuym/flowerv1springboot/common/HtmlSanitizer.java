package org.liuym.flowerv1springboot.common;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台富文本白名单清洗：只保留排版标签，属性一律丢弃（仅 a 的 href 走 SafeUrl 校验）。
 * 采用逐段扫描而非正则替换——白名单外的标签按文本转义，避免残缺尖括号重新拼出可执行标签。
 */
public final class HtmlSanitizer {

    private static final Set<String> ALLOWED = Set.of(
            "p", "br", "b", "strong", "i", "em", "u", "s", "h3", "h4", "ul", "ol", "li", "blockquote", "a");

    /** 属性段允许出现引号内的 >，因此按引号配对匹配 */
    private static final Pattern TAG = Pattern.compile(
            "<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");

    private static final Pattern HREF = Pattern.compile(
            "href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?>|<![a-zA-Z][\\s\\S]*?>");

    private static final Pattern ENTITY = Pattern.compile("&[a-zA-Z][a-zA-Z0-9]{1,10};|&#[0-9]{1,8};");

    private HtmlSanitizer() {
    }

    /** 入库前调用：返回只含白名单标签的 HTML */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String source = COMMENT.matcher(html).replaceAll("");
        StringBuilder out = new StringBuilder(source.length());
        Matcher tags = TAG.matcher(source);
        int cursor = 0;
        while (tags.find()) {
            out.append(escapeText(source, cursor, tags.start()));
            out.append(renderTag(tags.group(1), tags.group(2), tags.group(3)));
            cursor = tags.end();
        }
        out.append(escapeText(source, cursor, source.length()));
        return out.toString();
    }

    /** 摘要与空值校验用：剥离全部标签与实体后的纯文本 */
    public static String text(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String withoutComments = COMMENT.matcher(html).replaceAll(" ");
        String withoutTags = TAG.matcher(withoutComments).replaceAll(" ");
        String withoutEntities = ENTITY.matcher(withoutTags).replaceAll(" ");
        return withoutEntities.replaceAll("\\s+", " ").trim();
    }

    private static String renderTag(String closing, String name, String attributes) {
        String tag = name.toLowerCase();
        if (!ALLOWED.contains(tag)) {
            // 属性一并丢弃，避免 onerror= 之类的残片以文本形式出现在页面上
            return "&lt;" + closing + tag + "&gt;";
        }
        if (!closing.isEmpty()) {
            return "br".equals(tag) ? "" : "</" + tag + ">";
        }
        if ("br".equals(tag)) {
            return "<br>";
        }
        if (!"a".equals(tag)) {
            return "<" + tag + ">";
        }
        Matcher href = HREF.matcher(attributes);
        String url = href.find() ? href.group(1) != null ? href.group(1)
                : href.group(2) != null ? href.group(2) : href.group(3) : null;
        return SafeUrl.isSafe(url) ? "<a href=\"" + url.trim().replace("\"", "&quot;") + "\">" : "<a>";
    }

    /** 文本段只转义尖括号：& 保持原样，避免把 &nbsp; 二次转义成可见字符 */
    private static String escapeText(String source, int from, int to) {
        return source.substring(from, to).replace("<", "&lt;").replace(">", "&gt;");
    }
}
