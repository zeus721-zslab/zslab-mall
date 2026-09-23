package com.zslab.mall.audit.repository;

import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 클레임 처리 이력(Track 103 D-214): 클레임 행과 그 클레임에 연결된 배송(회수·교환품·재발송) 행을 한 목록으로 최신순 조회한다.
     * 두 조건 모두 {@code ix_audit_log_target}을 탄다. {@code deliveryIds}는 비어 있으면 안 된다(호출부가 분기).
     * 모든 변수는 :name 바인딩이다(SQL injection 위험 없음).
     */
    @Query(value = "SELECT a FROM AuditLog a WHERE (a.targetType = :claimType AND a.targetId = :claimId) "
            + "OR (a.targetType = :deliveryType AND a.targetId IN :deliveryIds) ORDER BY a.id DESC",
            countQuery = "SELECT COUNT(a) FROM AuditLog a WHERE (a.targetType = :claimType AND a.targetId = :claimId) "
                    + "OR (a.targetType = :deliveryType AND a.targetId IN :deliveryIds)")
    Page<AuditLog> findClaimHistory(
            @Param("claimType") PolymorphicTargetType claimType,
            @Param("claimId") Long claimId,
            @Param("deliveryType") PolymorphicTargetType deliveryType,
            @Param("deliveryIds") List<Long> deliveryIds,
            Pageable pageable);
}
