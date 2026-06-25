package com.zslab.mall.order.repository;

import com.zslab.mall.common.config.AuditingConfig;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.entity.OrderShippingSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Order Aggregate @DataJpaTest 공통 베이스(QB-12).
 *
 * <p>testcontainers MariaDB(11.4 LTS)를 싱글톤으로 기동하고 Flyway V1·V2를 자동 적용한다.
 * {@link AutoConfigureTestDatabase.Replace#NONE}으로 임베디드 치환을 막아 실 MariaDB 스키마에 대해 매핑을 검증한다.
 *
 * <p><b>FK 체크</b>: 상위 그래프(user·seller·product·product_variant·category)는 Track 7 소관이라 본 트랙에서 영속할 수 없으므로,
 * Order 외부 FK(buyer_id·product_id·variant_id·seller_id)는 {@link #disableForeignKeyChecks()}로 비활성화한다.
 * FK 무결성 자체는 V1 DDL·Flyway 적용으로 검증되며, 본 테스트는 매핑·cascade·파생 쿼리에 집중한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class) // @DataJpaTest는 JPA Auditing을 켜지 않음 → created_at·updated_at 주입 위해 명시 import
abstract class OrderDataJpaTestBase {

    static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        MARIADB.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired
    protected TestEntityManager entityManager;

    /** Order 외부 FK 검증을 비활성화한다(상위 그래프는 Track 7). 영속 직전 호출한다. */
    protected void disableForeignKeyChecks() {
        entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS = 0")
                .executeUpdate();
    }

    /** items 2건·snapshot 1건을 포함한 완전한 Order를 구성한다(미영속). */
    protected Order buildFullOrder(String orderNo) {
        Order order = Order.create(1L, orderNo, 0L, 3_000L);
        order.addItem(OrderItem.create(1L, 1L, 1L, 2, 5_000L, 10_000L));
        order.addItem(OrderItem.create(2L, 2L, 1L, 1, 3_000L, 3_000L));
        order.attachSnapshot(OrderShippingSnapshot.create(
                "홍길동", "010-0000-0000", "06236", "서울 강남대로 1", null, "101호", "부재 시 경비실"));
        return order;
    }
}
