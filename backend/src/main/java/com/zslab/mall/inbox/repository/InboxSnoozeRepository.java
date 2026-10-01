package com.zslab.mall.inbox.repository;

import com.zslab.mall.inbox.entity.InboxSnooze;
import com.zslab.mall.inbox.enums.InboxItemType;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxSnoozeRepository extends JpaRepository<InboxSnooze, Long> {

    /**
     * 보류 생성 또는 기한·사유 덮어쓰기. 조회 후 INSERT는 같은 항목 동시 요청(더블클릭)에서 uk 위반 500이 나므로 한 문장 upsert로 둔다.
     * 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음.
     */
    @Modifying
    @Query(value = "INSERT INTO inbox_snooze (owner_user_id, item_type, item_ref, until_at, reason, created_at) "
            + "VALUES (:ownerUserId, :itemType, :itemRef, :untilAt, :reason, :now) "
            + "ON DUPLICATE KEY UPDATE until_at = VALUES(until_at), reason = VALUES(reason)", nativeQuery = true)
    void upsert(@Param("ownerUserId") Long ownerUserId, @Param("itemType") String itemType, @Param("itemRef") String itemRef,
            @Param("untilAt") LocalDateTime untilAt, @Param("reason") String reason, @Param("now") LocalDateTime now);

    long deleteByOwnerUserIdAndItemTypeAndItemRef(Long ownerUserId, InboxItemType itemType, String itemRef);
}
