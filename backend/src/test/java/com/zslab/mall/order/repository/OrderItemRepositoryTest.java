package com.zslab.mall.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link OrderItemRepository} @DataJpaTest. Order 저장 시 cascade PERSIST로 OrderItem이 함께 영속되는지·findByOrderId 검증.
 */
class OrderItemRepositoryTest extends OrderDataJpaTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Test
    @DisplayName("cascade PERSIST: Order 저장 시 OrderItem 2건 함께 영속·public_id(oit_) 생성")
    void cascadePersistsItems() {
        disableForeignKeyChecks();
        Order saved = orderRepository.saveAndFlush(buildFullOrder("20260625-ITEM01"));
        entityManager.clear();

        List<OrderItem> items = orderItemRepository.findByOrderId(saved.getId());

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getId()).isNotNull();
            assertThat(item.getPublicId()).startsWith("oit_").hasSize(30);
        });
    }

    @Test
    @DisplayName("findByOrderIdIn: 다건 주문 품목 조회")
    void findByOrderIdIn() {
        disableForeignKeyChecks();
        Order a = orderRepository.saveAndFlush(buildFullOrder("20260625-ITEM02"));
        Order b = orderRepository.saveAndFlush(buildFullOrder("20260625-ITEM03"));
        entityManager.clear();

        List<OrderItem> items = orderItemRepository.findByOrderIdIn(List.of(a.getId(), b.getId()));

        assertThat(items).hasSize(4);
    }
}
