package org.liuym.flowerv1springboot.service.impl;

import jakarta.persistence.criteria.Predicate;
import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.repository.AdminAuditLogRepository;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class AdminAuditServiceImpl implements AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditServiceImpl.class);

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Override
    public void record(AdminAuditLog entry) {
        try {
            auditLogRepository.save(entry);
        } catch (RuntimeException e) {
            // 留痕属于旁路能力：表缺失或写入异常时只降级为应用日志，不能连带业务请求失败
            log.warn("写入操作审计日志失败: {} {} -> {}", entry.getMethod(), entry.getUri(), e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminAuditLog> search(String keyword, String module, Pageable pageable) {
        return auditLogRepository.findAll(specification(keyword, module), pageable);
    }

    private Specification<AdminAuditLog> specification(String keyword, String module) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("operatorName")), like),
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("uri")), like),
                        cb.like(cb.lower(root.get("detail")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
