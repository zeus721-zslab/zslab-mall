package com.zslab.mall.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * {@link OrderRepository} @DataJpaTest. save·findByPublicId·findByOrderNo·existsByOrderNo + @PrePersist public_id 생성 검증.
 */
class OrderRepositoryTest extends OrderDataJpaTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("save: @PrePersist로 public_id(ord_) 생성·id 할당")
    void save_generatesPublicId() {
        disableForeignKeyChecks();
        Order saved = orderRepository.saveAndFlush(buildFullOrder("20260625-AAAAAA"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPublicId()).startsWith("ord_").hasSize(30);
    }

    @Test
    @DisplayName("findByPublicId: 영속 후 조회")
    void findByPublicId() {
        disableForeignKeyChecks();
        Order saved = orderRepository.saveAndFlush(buildFullOrder("20260625-BBBBBB"));
        entityManager.clear();

        Optional<Order> found = orderRepository.findByPublicId(saved.getPublicId());

        assertThat(found).isPresent();
        assertThat(found.get().getOrderNo()).isEqualTo("20260625-BBBBBB");
    }

    @Test
    @DisplayName("findByOrderNo·existsByOrderNo")
    void findAndExistsByOrderNo() {
        disableForeignKeyChecks();
        orderRepository.saveAndFlush(buildFullOrder("20260625-CCCCCC"));
        entityManager.clear();

        assertThat(orderRepository.findByOrderNo("20260625-CCCCCC")).isPresent();
        assertThat(orderRepository.existsByOrderNo("20260625-CCCCCC")).isTrue();
        assertThat(orderRepository.existsByOrderNo("20260625-NONE99")).isFalse();
    }

    @Test
    @DisplayName("자동취소 조회: PENDING_PAYMENT + createdAt≤threshold만 선별(status·시각 이중 필터·D-153 Phase 1)")
    void findAutoCancelTargets_filtersByStatusAndCreatedAt() {
        disableForeignKeyChecks();
        // buildFullOrder는 PENDING_PAYMENT로 생성됨(createdAt=now·auditing). PAID 1건은 status 필터로 제외 대상.
        Order pending = orderRepository.saveAndFlush(buildFullOrder("20260709-PEND01"));
        Order paid = buildFullOrder("20260709-PAID01");
        paid.applyResolvedStatus(OrderStatus.PAID);
        orderRepository.saveAndFlush(paid);
        entityManager.clear();

        // threshold 미래(now+1분): PENDING은 포함, PAID는 status 필터로 제외
        List<Order> included = orderRepository.findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                OrderStatus.PENDING_PAYMENT, LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 100));
        assertThat(included).extracting(Order::getPublicId).containsExactly(pending.getPublicId());

        // threshold 과거(now-1분): createdAt(now) > threshold → 시각 필터로 제외
        List<Order> excluded = orderRepository.findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAsc(
                OrderStatus.PENDING_PAYMENT, LocalDateTime.now().minusMinutes(1), PageRequest.of(0, 100));
        assertThat(excluded).isEmpty();
    }
}
