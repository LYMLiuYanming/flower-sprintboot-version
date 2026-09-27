package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReviewService {

    ReviewView submit(UUID userId, ContentDtos.ReviewForm form);

    Page<ReviewView> listByProduct(UUID productId, Pageable pageable);
}
