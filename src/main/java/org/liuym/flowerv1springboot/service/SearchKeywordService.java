package org.liuym.flowerv1springboot.service;

import java.util.List;

public interface SearchKeywordService {

    /**
     * 累计一次检索词（旁路能力：失败只打日志，绝不影响检索本身）
     */
    void accumulate(String keyword);

    /**
     * 输入联想：搜索词库 + 商品名 + 标签 + 分类名，去重后按 limit 截断；keyword 为空则返回热门搜索词
     */
    List<String> suggest(String keyword, int limit);
}
