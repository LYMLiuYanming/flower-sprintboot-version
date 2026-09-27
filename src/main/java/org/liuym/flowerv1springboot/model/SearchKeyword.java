package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 搜索词累计：一个词一行，命中则计数 +1，用于「热门搜索词」与输入联想
 */
@Data
@Entity
@Table(name = "search_keyword", schema = "public")
public class SearchKeyword {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "keyword", nullable = false, length = 50, unique = true)
    private String keyword;

    @Column(name = "search_count", nullable = false)
    private Integer searchCount = 1;

    @Column(name = "last_searched_at", nullable = false)
    private LocalDateTime lastSearchedAt;
}
