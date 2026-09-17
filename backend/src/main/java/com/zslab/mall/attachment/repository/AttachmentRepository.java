package com.zslab.mall.attachment.repository;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /**
     * 서빙 URL(file_path 저장값과 동일 문자열)로 첨부를 조회한다(Track 82 D-176·인가 서빙). 썸네일 키는 원본 후보가 여러 형식일 수 있어
     * IN으로 받는다. ix_attachment_file_path(prefix 255·V26) 탐색이며 소프트삭제 필터가 자동 적용된다.
     */
    List<Attachment> findByFilePathIn(Collection<String> filePaths);

    /** 사용자별 미연결 첨부 수(D-174 보유 상한 판정·ix_attachment_target(target_type, target_id IS NULL) 탐색). */
    long countByTargetTypeAndTargetIdIsNullAndUploadedBy(PolymorphicTargetType targetType, Long uploadedBy);

    /**
     * 정리 대상 미연결 첨부 id를 배치 상한으로 조회한다(D-174·AttachmentCleanupScheduler). 기준시각(threshold=now−유예)은 스케줄러가
     * 계산해 전달한다. 소프트삭제 필터(@SQLRestriction)가 자동 적용된다. 모든 변수는 :targetType·:threshold 바인딩이다.
     */
    @Query("SELECT a.id FROM Attachment a WHERE a.targetType = :targetType AND a.targetId IS NULL "
            + "AND a.createdAt < :threshold ORDER BY a.id ASC")
    List<Long> findUnlinkedIdsCreatedBefore(
            @Param("targetType") PolymorphicTargetType targetType,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    /**
     * 미연결 첨부 행을 조건부로 물리 삭제한다(D-174). {@code target_id IS NULL}을 WHERE에 재확인해 조회~삭제 사이에 클레임에 연결된
     * 첨부를 보호한다(영향 행 0 = 보존). 소프트삭제 엔티티지만 미연결 첨부는 어떤 대상에도 속하지 않아 이력 보존 가치가 없으므로
     * 하드 삭제한다. 모든 변수는 :id 바인딩이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Attachment a WHERE a.id = :id AND a.targetId IS NULL")
    int deleteIfUnlinked(@Param("id") Long id);

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
