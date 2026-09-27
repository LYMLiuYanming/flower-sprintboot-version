package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTokenizerTest {

    @Test
    void blankInputYieldsNoTerms() {
        assertTrue(SearchTokenizer.terms(null).isEmpty());
        assertTrue(SearchTokenizer.terms("   ,,  ").isEmpty());
    }

    @Test
    void shortCjkRunsStayWhole() {
        assertEquals(List.of("玫瑰"), SearchTokenizer.terms("玫瑰"));
        assertEquals(List.of("红"), SearchTokenizer.terms("红"));
    }

    @Test
    void longCjkRunSplitsIntoBigrams() {
        assertEquals(List.of("康乃", "乃馨"), SearchTokenizer.terms("康乃馨"));
    }

    @Test
    void latinAndDigitsKeepWholeWordAndLowerCase() {
        assertEquals(List.of("rose99"), SearchTokenizer.terms("ROSE99"));
        assertEquals(List.of("gift", "礼盒"), SearchTokenizer.terms("gift 礼盒"));
    }

    @Test
    void punctuationSeparatesTermsAndDuplicatesCollapse() {
        assertEquals(List.of("玫瑰", "百合"), SearchTokenizer.terms("玫瑰、百合；玫瑰"));
    }

    @Test
    void termCountIsCapped() {
        List<String> terms = SearchTokenizer.terms("一 二 三 四 五 六 七 八");
        assertEquals(6, terms.size());
        assertEquals(List.of("一", "二", "三", "四", "五", "六"), terms);
    }
}
