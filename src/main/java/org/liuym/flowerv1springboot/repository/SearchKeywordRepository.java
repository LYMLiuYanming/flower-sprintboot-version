package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.SearchKeyword;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SearchKeywordRepository extends JpaRepository<SearchKeyword, UUID> {

    /**
     * 累计一次搜索：唯一键冲突则计数 +1。
     * 用原生 upsert 而非「查—改—插」，避免并发搜索互相覆盖计数。
     */
    @Modifying
    @Query(value = """
            INSERT INTO search_keyword (id, keyword, search_count, last_searched_at)
            VALUES (gen_random_uuid(), :keyword, 1, now())
            ON CONFLICT (keyword) DO UPDATE
            SET search_count = search_keyword.search_count + 1, last_searched_at = now()
            """, nativeQuery = true)
    void accumulate(@Param("keyword") String keyword);

    @Query("""
            SELECT k.keyword FROM SearchKeyword k
            WHERE LOWER(k.keyword) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY k.searchCount DESC, k.lastSearchedAt DESC
            """)
    List<String> findMatching(@Param("term") String term, Pageable pageable);

    @Query("SELECT k.keyword FROM SearchKeyword k ORDER BY k.searchCount DESC, k.lastSearchedAt DESC")
    List<String> findHot(Pageable pageable);
}
