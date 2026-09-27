package org.liuym.flowerv1springboot.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索词切分：无第三方分词库（新增 jar 需重启进程，见待办清单 D5 教训），
 * 采用「拉丁词整词 + 中文二元组」的轻量方案，让「红 玫瑰」「红玫瑰礼盒」这类
 * 多词/长词查询也能命中，而不是退化成一次整串 LIKE。
 */
public final class SearchTokenizer {

    /** 一次检索最多参与匹配的词数，避免超长输入拼出巨型 SQL */
    private static final int MAX_TERMS = 6;

    private SearchTokenizer() {
    }

    public static List<String> terms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isCjk(c)) {
                flushLatin(terms, latin);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(terms, cjk);
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(terms, latin);
                flushCjk(terms, cjk);
            }
        }
        flushLatin(terms, latin);
        flushCjk(terms, cjk);
        List<String> list = new ArrayList<>(terms);
        return list.size() > MAX_TERMS ? list.subList(0, MAX_TERMS) : list;
    }

    private static void flushLatin(Set<String> terms, StringBuilder latin) {
        if (latin.length() > 0) {
            terms.add(latin.toString());
            latin.setLength(0);
        }
    }

    /**
     * 中文段：两字以内整段匹配；更长则拆成相邻二元组（红玫瑰 → 红玫 + 玫瑰），
     * 既保留「玫瑰」这种真实词，也不至于退化成逐字匹配带来的大量噪音。
     */
    private static void flushCjk(Set<String> terms, StringBuilder cjk) {
        if (cjk.length() == 0) {
            return;
        }
        if (cjk.length() <= 2) {
            terms.add(cjk.toString());
        } else {
            for (int i = 0; i + 2 <= cjk.length(); i++) {
                terms.add(cjk.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
