package com.zslab.mall.attachment.repository;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByPublicId(String publicId);

    List<Attachment> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);

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
