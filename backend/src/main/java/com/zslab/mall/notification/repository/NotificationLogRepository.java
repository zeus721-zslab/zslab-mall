package com.zslab.mall.notification.repository;

import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);

    List<NotificationLog> findByRecipientUserIdAndStatus(Long recipientUserId, NotificationLogStatus status);

    /** 대상·템플릿·상태가 같은 마지막 발송 1건(ix_notification_log_target로 대상을 좁힌다 · 셀러 지연 독촉 쿨다운 D-252). */
    Optional<NotificationLog> findFirstByTargetTypeAndTargetIdAndTemplateCodeAndStatusOrderBySentAtDesc(
            PolymorphicTargetType targetType, Long targetId, String templateCode, NotificationLogStatus status);
}
