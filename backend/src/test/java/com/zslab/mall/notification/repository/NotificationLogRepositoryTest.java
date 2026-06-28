package com.zslab.mall.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.notification.entity.NotificationLog;
import com.zslab.mall.notification.enums.NotificationChannel;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link NotificationLogRepository} @DataJpaTest — CRUD·append-only·status ENUM·polymorphic target 검증.
 */
class NotificationLogRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    private NotificationLog createLog(Long recipientUserId, NotificationChannel channel,
            PolymorphicTargetType targetType, Long targetId) {
        return NotificationLog.create(recipientUserId, channel, "TPL_ORDER_CREATED",
            targetType, targetId, "주문 완료", "주문이 완료되었습니다.");
    }

    @Test
    @DisplayName("save+findById 성공: channel·status PENDING·targetType·templateCode 확인")
    void save_findById_success() {
        NotificationLog saved = notificationLogRepository.saveAndFlush(
            createLog(1L, NotificationChannel.EMAIL, PolymorphicTargetType.ORDER, 10L));
        entityManager.clear();

        Optional<NotificationLog> found = notificationLogRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(found.get().getStatus()).isEqualTo(NotificationLogStatus.PENDING);
        assertThat(found.get().getTargetType()).isEqualTo(PolymorphicTargetType.ORDER);
        assertThat(found.get().getTargetId()).isEqualTo(10L);
        assertThat(found.get().getTemplateCode()).isEqualTo("TPL_ORDER_CREATED");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByTargetTypeAndTargetId: polymorphic 조회 목록 반환")
    void findByTargetTypeAndTargetId_success() {
        notificationLogRepository.saveAndFlush(
            createLog(1L, NotificationChannel.EMAIL, PolymorphicTargetType.PAYMENT, 20L));
        notificationLogRepository.saveAndFlush(
            createLog(2L, NotificationChannel.PUSH, PolymorphicTargetType.PAYMENT, 20L));
        notificationLogRepository.saveAndFlush(
            createLog(1L, NotificationChannel.SMS, PolymorphicTargetType.ORDER, 20L));
        entityManager.clear();

        List<NotificationLog> results = notificationLogRepository
            .findByTargetTypeAndTargetId(PolymorphicTargetType.PAYMENT, 20L);

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("findByRecipientUserIdAndStatus: 수신자·상태 필터 조회")
    void findByRecipientUserIdAndStatus_success() {
        notificationLogRepository.saveAndFlush(
            createLog(5L, NotificationChannel.IN_APP, PolymorphicTargetType.ORDER, 30L));
        notificationLogRepository.saveAndFlush(
            createLog(5L, NotificationChannel.EMAIL, PolymorphicTargetType.ORDER, 31L));
        entityManager.clear();

        List<NotificationLog> results = notificationLogRepository
            .findByRecipientUserIdAndStatus(5L, NotificationLogStatus.PENDING);

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("channel ENUM 외 값 삽입 → PersistenceException (ENUM constraint)")
    void insert_invalidChannel_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO notification_log (recipient_user_id, channel, template_code, target_type, target_id, status, created_at) "
                + "VALUES (1, 'INVALID_CHANNEL', 'TPL_X', 'ORDER', 1, 'PENDING', NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("status ENUM 외 값 삽입 → PersistenceException (ENUM constraint)")
    void insert_invalidStatus_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO notification_log (recipient_user_id, channel, template_code, target_type, target_id, status, created_at) "
                + "VALUES (1, 'EMAIL', 'TPL_X', 'ORDER', 1, 'INVALID_STATUS', NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
