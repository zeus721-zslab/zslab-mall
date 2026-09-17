package com.zslab.mall.attachment.repository;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByPublicId(String publicId);

    List<Attachment> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);

    /**
     * 미연결 첨부를 대상에 연결하는 조건부 UPDATE(D-172·Q5). {@code target_id IS NULL AND uploaded_by = :uploadedBy}를 WHERE에 넣어 같은 첨부를
     * 동시에 연결하려는 두 요청 중 한쪽만 영향 행 1을 얻는다(영향 0 = 이미 연결됐거나 타인 파일 → 호출부 400·TX 롤백).
     * flushAutomatically로 선행 INSERT(클레임)를 먼저 내보낸다. 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Attachment a SET a.targetId = :targetId, a.displayOrder = :displayOrder "
            + "WHERE a.id = :id AND a.targetId IS NULL AND a.uploadedBy = :uploadedBy")
    int linkIfUnlinked(@Param("id") Long id, @Param("uploadedBy") Long uploadedBy,
            @Param("targetId") Long targetId, @Param("displayOrder") int displayOrder);

    /** 클레임 첨부 연결용 일괄 조회(Track 81-B D-171). 소유권·미연결 검증은 서비스가 한다. */
    List<Attachment> findByPublicIdIn(Collection<String> publicIds);

    /** 대상 1건의 첨부(순서 보존). */
    List<Attachment> findByTargetTypeAndTargetIdOrderByDisplayOrderAsc(PolymorphicTargetType targetType, Long targetId);

    /** 관리자 주문 상세 등 배치 enrich(대상 여러 건·순서 보존). */
    List<Attachment> findByTargetTypeAndTargetIdInOrderByTargetIdAscDisplayOrderAsc(
            PolymorphicTargetType targetType, Collection<Long> targetIds);

    /** 관리자 클레임 목록 첨부 개수(1쿼리 배치). 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음. */
    @Query("SELECT a.targetId AS targetId, COUNT(a) AS attachmentCount FROM Attachment a "
            + "WHERE a.targetType = :targetType AND a.targetId IN :targetIds GROUP BY a.targetId")
    List<AttachmentCountProjection> countByTargetTypeAndTargetIdIn(
            @Param("targetType") PolymorphicTargetType targetType, @Param("targetIds") Collection<Long> targetIds);
}
