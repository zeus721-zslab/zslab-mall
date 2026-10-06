package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.entity.OrderShippingSnapshot;
import com.zslab.mall.support.AbstractDataJpaTest;

/**
 * Order Aggregate @DataJpaTest 공통 베이스(QB-12).
 *
 * <p>Track 63: 슬라이스·싱글톤 컨테이너·TestEntityManager·FK 복원은 {@link AbstractDataJpaTest}로 승격.
 * 본 클래스는 Order 외부 FK 비활성 헬퍼와 완전 그래프 빌더만 보유한다.
 *
 * <p><b>FK 체크</b>: 상위 그래프(user·seller·product·product_variant·category)는 Track 7 소관이라 본 트랙에서 영속할 수 없으므로,
 * Order 외부 FK(buyer_id·product_id·variant_id·seller_id)는 {@link #disableForeignKeyChecks()}로 비활성화한다.
 * 복원(=1)은 {@link AbstractDataJpaTest#restoreForeignKeyChecks()}가 @AfterEach 최후에 무조건 수행한다.
 */
abstract class OrderDataJpaTestBase extends AbstractDataJpaTest {

    /** Order 외부 FK 검증을 비활성화한다(상위 그래프는 Track 7). 영속 직전 호출한다. */
    protected void disableForeignKeyChecks() {
        entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS = 0")
                .executeUpdate();
    }

    /**
     * items 2건을 포함한 Order를 구성한다(미영속). 배송지 스냅샷은 Order가 보유하지 않으므로(cascade 없음)
     * 필요한 테스트가 {@link #buildSnapshotFor}로 만들어 직접 저장한다.
     */
    protected Order buildFullOrder(String orderNo) {
        Order order = Order.create(1L, orderNo, 0L, 3_000L);
        order.addItem(OrderItem.create(1L, 1L, 1L, "테스트 상품", 2, 5_000L, 10_000L, 1000));
        order.addItem(OrderItem.create(2L, 2L, 1L, "테스트 상품", 1, 3_000L, 3_000L, 1000));
        return order;
    }

    /** 주어진 주문에 연결된 배송지 스냅샷을 구성한다(미영속). */
    protected OrderShippingSnapshot buildSnapshotFor(Order order) {
        OrderShippingSnapshot snapshot = OrderShippingSnapshot.create(
                "홍길동", "010-0000-0000", "06236", "서울 강남대로 1", null, "101호", "부재 시 경비실");
        order.attachSnapshot(snapshot);
        return snapshot;
    }
}
