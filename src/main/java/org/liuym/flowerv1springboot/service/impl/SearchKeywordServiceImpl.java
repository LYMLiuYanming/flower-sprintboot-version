package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.SearchKeywordRepository;
import org.liuym.flowerv1springboot.service.SearchKeywordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SearchKeywordServiceImpl implements SearchKeywordService {

    private static final int MAX_TERM_LENGTH = 50;
    private static final int MAX_SUGGEST = 10;

    @Autowired
    private SearchKeywordRepository searchKeywordRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Override
    @Transactional
    public void accumulate(String keyword) {
        String term = normalized(keyword);
        if (term == null) {
            return;
        }
        searchKeywordRepository.accumulate(term);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> suggest(String keyword, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_SUGGEST);
        Set<String> words = new LinkedHashSet<>();
        String term = normalized(keyword);
        if (term == null) {
            words.addAll(searchKeywordRepository.findHot(PageRequest.of(0, size)));
            return List.copyOf(words);
        }
        words.addAll(searchKeywordRepository.findMatching(term, PageRequest.of(0, size)));
        words.addAll(categoryRepository.findNamesMatching(term, PageRequest.of(0, size)));
        productRepository.findTagsMatching(term).forEach(tags -> words.addAll(splitTags(tags, term)));
        words.addAll(productRepository.findNamesMatching(term, PageRequest.of(0, size)));
        return words.stream().limit(size).toList();
    }

    /** 逗号/顿号分隔的标签串，只保留真正含关键词的标签词 */
    private Set<String> splitTags(String tags, String term) {
        Set<String> hits = new LinkedHashSet<>();
        for (String tag : tags.split("[,，、|]")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty() && trimmed.toLowerCase().contains(term)) {
                hits.add(trimmed);
            }
        }
        return hits;
    }

    /** 入库前统一收口：小写、压缩空白、限长；空或超长视为无效 */
    private String normalized(String keyword) {
        if (keyword == null) {
            return null;
        }
        String term = keyword.trim().replaceAll("\\s+", " ").toLowerCase();
        return term.isEmpty() || term.length() > MAX_TERM_LENGTH ? null : term;
    }
}
