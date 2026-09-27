package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserIdOrderByIsDefaultDescCreatedAtDesc(UUID userId);

    Optional<Address> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Address a SET a.isDefault = false, a.updatedAt = CURRENT_TIMESTAMP WHERE a.user.id = :userId")
    int clearDefault(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Address a SET a.isDefault = true, a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id AND a.user.id = :userId")
    int markDefault(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Address a WHERE a.id = :id AND a.user.id = :userId")
    int deleteByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);
}
