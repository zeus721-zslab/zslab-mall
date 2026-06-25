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
 * Order 저장 시 cascade PERSIST로 Snapshot이 함께 영속되는지·findByOrderId·belongsTo 검증.
 */
class OrderShippingSnapshotRepositoryTest extends OrderDataJpaTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderShippingSnapshotRepository snapshotRepository;

    @Test
    @DisplayName("cascade PERSIST: Order 저장 시 Snapshot 자동 영속·findByOrderId 조회")
    void cascadePersistsSnapshot() {
        disableForeignKeyChecks();
        Order saved = orderRepository.saveAndFlush(buildFullOrder("20260625-SNAP01"));
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
