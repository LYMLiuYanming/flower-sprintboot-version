package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID>, JpaSpecificationExecutor<AdminAuditLog> {
}
