package com.zslab.mall.notification.repository;

import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByTargetTypeAndTargetId(PolymorphicTargetType targetType, Long targetId);

    List<NotificationLog> findByRecipientUserIdAndStatus(Long recipientUserId, NotificationLogStatus status);
}
