package com.zslab.mall.audit.repository;

import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Optional<AuditLog> findByPublicId(String publicId);

    List<AuditLog> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);
}
