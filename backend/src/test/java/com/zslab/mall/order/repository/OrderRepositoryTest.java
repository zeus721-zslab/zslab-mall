package com.zslab.mall.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.entity.Order;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
