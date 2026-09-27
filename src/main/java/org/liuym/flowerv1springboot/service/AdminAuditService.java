package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminAuditService {

    /**
     * 写入一条留痕；失败只记日志，绝不影响业务请求本身
     */
    void record(AdminAuditLog entry);

    /**
     * @param keyword 命中操作人/动作/路径/明细任一字段
     * @param module  模块中文名，空则不限
     */
    Page<AdminAuditLog> search(String keyword, String module, Pageable pageable);
}
