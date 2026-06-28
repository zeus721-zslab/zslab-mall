package com.zslab.mall.delivery.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.enums.DeliveryCarrier;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link DeliveryRepository} @DataJpaTest — CRUD·public_id·tracking_no UK·FK·ENUM constraint 검증.
 *
 * <p>order_item은 Order Aggregate 외부 — nativeQuery seed(FK_CHECKS=0·LT-02 try-finally 복원).
 */
class DeliveryRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private DeliveryRepository deliveryRepository;

    /**
     * order + order_item을 FK_CHECKS=0으로 시딩 후 복원한다(LT-02).
     *
     * @param orderPublicId  CHAR(30) — "ord_" + 26자
     * @param orderItemPublicId  CHAR(30) — "oit_" + 26자
     */
    private long seedOrderItem(String orderPublicId, String orderItemPublicId, String orderNo) {
        entityManager.getEntityManager()
            .createNativeQuery("SET FOREIGN_KEY_CHECKS=0")
            .executeUpdate();
        try {
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO `order` "
                + "(public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, created_at, updated_at) "
                + "VALUES ('" + orderPublicId + "', 1, '" + orderNo + "', 'PAID', 10000, 0, 0, NOW(6), NOW(6))")
                .executeUpdate();
            long orderId = ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();

            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO order_item "
                + "(public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, total_price, item_status, created_at, updated_at) "
                + "VALUES ('" + orderItemPublicId + "', " + orderId + ", 1, 1, 1, 1, 10000, 10000, 'PAID', NOW(6), NOW(6))")
                .executeUpdate();
            return ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        } finally {
            entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS=1")
                .executeUpdate();
        }
    }

    @Test
    @DisplayName("save+findById 성공: public_id dlv_ prefix·CHAR(30)·orderItemId·status READY 확인")
    void save_findById_success_withPublicId() {
        long orderItemId = seedOrderItem(
            "ord_01234567890123456789012345",
            "oit_01234567890123456789012345",
            "DLV-TEST-001");
        Delivery saved = deliveryRepository.saveAndFlush(Delivery.create(orderItemId, DeliveryCarrier.CJ));
        entityManager.clear();

        Optional<Delivery> found = deliveryRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getPublicId()).startsWith("dlv_").hasSize(30);
        assertThat(found.get().getOrderItemId()).isEqualTo(orderItemId);
        assertThat(found.get().getCarrier()).isEqualTo(DeliveryCarrier.CJ);
        assertThat(found.get().getTrackingNo()).isNull();
        assertThat(found.get().getStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("tracking_no NULL 다건 삽입 허용: MariaDB UNIQUE KEY에서 NULL 비교 제외(DLV-1)")
    void insert_trackingNoNull_multipleAllowed() {
        long orderItemId = seedOrderItem(
            "ord_21234567890123456789012345",
            "oit_21234567890123456789012345",
            "DLV-TEST-002");
        Delivery delivery1 = deliveryRepository.saveAndFlush(Delivery.create(orderItemId, DeliveryCarrier.POST));
        Delivery delivery2 = deliveryRepository.saveAndFlush(Delivery.create(orderItemId, DeliveryCarrier.LOGEN));

        assertThat(delivery1.getTrackingNo()).isNull();
        assertThat(delivery2.getTrackingNo()).isNull();
        assertThat(delivery1.getId()).isNotEqualTo(delivery2.getId());
    }

    @Test
    @DisplayName("tracking_no NOT NULL 중복 삽입 → PersistenceException (UK 위반·nativeQuery)")
    void insert_duplicateTrackingNoNotNull_throwsPersistenceException() {
        long orderItemId = seedOrderItem(
            "ord_31234567890123456789012345",
            "oit_31234567890123456789012345",
            "DLV-TEST-003");
        entityManager.getEntityManager().createNativeQuery(
            "INSERT INTO delivery (public_id, order_item_id, carrier, tracking_no, status, created_at, updated_at) "
            + "VALUES ('dlv_01234567890123456789012345', " + orderItemId + ", 'CJ', 'CJ-DUP-TRACK-001', 'SHIPPING', NOW(6), NOW(6))")
            .executeUpdate();

        assertThatThrownBy(() ->
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO delivery (public_id, order_item_id, carrier, tracking_no, status, created_at, updated_at) "
                + "VALUES ('dlv_11234567890123456789012345', " + orderItemId + ", 'CJ', 'CJ-DUP-TRACK-001', 'SHIPPING', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("order_item_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidOrderItemId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO delivery (public_id, order_item_id, carrier, status, created_at, updated_at) "
                    + "VALUES ('dlv_21234567890123456789012345', 99999, 'CJ', 'READY', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("carrier ENUM 외 값 삽입 → PersistenceException (ENUM constraint)")
    void insert_invalidCarrier_throwsPersistenceException() {
        long orderItemId = seedOrderItem(
            "ord_41234567890123456789012345",
            "oit_41234567890123456789012345",
            "DLV-TEST-004");
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO delivery (public_id, order_item_id, carrier, status, created_at, updated_at) "
                    + "VALUES ('dlv_31234567890123456789012345', " + orderItemId + ", 'INVALID_CARRIER', 'READY', NOW(6), NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
