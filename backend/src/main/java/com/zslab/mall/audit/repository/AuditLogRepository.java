package com.zslab.mall.audit.repository;

import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Optional<AuditLog> findByPublicId(String publicId);

    List<AuditLog> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);

    /**
     * 대상 1건의 처리 이력을 최신순으로 조회한다(Track 101-A 관리자 "처리 이력" 섹션). id DESC = 적재 순서 역순이며,
     * 같은 트랜잭션에서 여러 행이 생겨 created_at이 동률일 때도 순서가 안정적이다(created_at DESC 대신 id DESC를 쓰는 이유).
     * {@code ix_audit_log_target(target_type, target_id)} 인덱스를 탄다.
     */
    Page<AuditLog> findByTargetTypeAndTargetIdOrderByIdDesc(
            PolymorphicTargetType targetType, Long targetId, Pageable pageable);
}
