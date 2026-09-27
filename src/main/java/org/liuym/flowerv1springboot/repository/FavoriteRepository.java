package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    @Query("SELECT f FROM Favorite f JOIN FETCH f.product WHERE f.user.id = :userId ORDER BY f.createdAt DESC")
    Page<Favorite> findPageByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT f FROM Favorite f JOIN FETCH f.product WHERE f.user.id = :userId ORDER BY f.createdAt DESC")
    List<Favorite> findByUserId(@Param("userId") UUID userId);

    Optional<Favorite> findByUserIdAndProductId(UUID userId, UUID productId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    long countByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.product.id = :productId")
    int deleteByProductId(@Param("productId") UUID productId);
}
