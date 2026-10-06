package com.zslab.mall.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderShippingSnapshot;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link OrderShippingSnapshotRepository} @DataJpaTest.
 * Order 저장 뒤 Snapshot 명시 저장(cascade 없음·퀄리티 B 3-1)·findByOrderId·belongsTo 검증.
 */
class OrderShippingSnapshotRepositoryTest extends OrderDataJpaTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderShippingSnapshotRepository snapshotRepository;

    @Test
    @DisplayName("명시 저장: Order 저장 뒤 Snapshot 저장 → findByOrderId 조회·order_id 귀속")
    void explicitSavePersistsSnapshot() {
        disableForeignKeyChecks();
        Order order = buildFullOrder("20260625-SNAP01");
        OrderShippingSnapshot snapshot = buildSnapshotFor(order);
        Order saved = orderRepository.saveAndFlush(order);
        snapshotRepository.saveAndFlush(snapshot);
        entityManager.clear();

        Optional<OrderShippingSnapshot> found = snapshotRepository.findByOrderId(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isNotNull();
        assertThat(found.get().getRecipientName()).isEqualTo("홍길동");
        // 역참조 getter 미노출 대체 도메인 메서드(QB-10)
        assertThat(found.get().belongsTo(saved.getId())).isTrue();
        assertThat(found.get().belongsTo(-1L)).isFalse();
    }
}
