package com.zslab.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.order.command.CreateOrderCommand;
import com.zslab.mall.order.command.OrderItemCommand;
import com.zslab.mall.order.command.ShippingAddressCommand;
import com.zslab.mall.order.service.OrderService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 주문 생성 트랜잭션 롤백 통합 테스트(실 MariaDB·Flyway V1~V5·GAP-TECH-1). buyer_id가 유효한 Order INSERT가 성공하더라도
 * OrderItem FK(product_id·variant_id) 위반 발생 시 Order·OrderItem 모두 롤백됨을 실 DB로 검증한다.
 *
 * <p><b>시나리오</b>: FK_CHECKS=0으로 user(id=9001)를 시드 → OrderService.createOrder()가 buyer_id=9001·존재하지 않는
 * product_id/variant_id 2건으로 호출됨 → {@code @Transactional} 경계 내 flush 시 FK 위반 → 전체 롤백 → DB count=0 검증.
 *
 * <p><b>트랜잭션</b>: OrderService.createOrder는 자체 {@code @Transactional}이므로 테스트 메서드에 @Transactional을 두지 않는다.
 * 시드/정리는 {@link TransactionTemplate}·검증은 {@link JdbcTemplate}으로 처리한다(RefundWebhookIntegrationTest 패턴 준용).
 */
@SpringBootTest
class OrderTransactionRollbackTest {

    private static final long BUYER_ID = 9001L;

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
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedUser();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("rollback (GAP-TECH-1): OrderItem FK 위반 시 Order·OrderItem 전체 롤백·DB count=0")
    void createOrder_invalidItem_rollbackAll() {
        CreateOrderCommand command = new CreateOrderCommand(
                BUYER_ID,
                List.of(
                        new OrderItemCommand(99991L, 99991L, 1L, 1, 5000L, 5000L),
                        new OrderItemCommand(99992L, 99992L, 1L, 2, 3000L, 6000L)),
                new ShippingAddressCommand(
                        "홍길동", "010-1234-5678", "06236", "서울 강남대로 1", null, "101호", null),
                0L, 0L);

        assertThatThrownBy(() -> orderService.createOrder(command))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orderCount()).isZero();
        assertThat(orderItemCount()).isZero();
    }

    /**
     * user(id=9001)를 FK_CHECKS=0으로 시드한다(user 자체 상위 그래프 생략).
     * FK_CHECKS는 세션 변수이므로 커넥션 풀 반납 전에 1로 복원한다(이후 createOrder FK 검증 보장).
     */
    private void seedUser() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) "
                    + "VALUES (?, 'usr_track6rollback000000000000', NOW(6), NOW(6))",
                    BUYER_ID);
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        });
    }

    private void cleanup() {
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("DELETE FROM order_item WHERE order_id IN "
                    + "(SELECT id FROM `order` WHERE buyer_id = ?)", BUYER_ID);
            jdbc.update("DELETE FROM `order` WHERE buyer_id = ?", BUYER_ID);
            jdbc.update("DELETE FROM `user` WHERE id = ?", BUYER_ID);
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        });
    }

    private int orderCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE buyer_id = ?", Integer.class, BUYER_ID);
    }

    private int orderItemCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_item WHERE order_id IN "
                        + "(SELECT id FROM `order` WHERE buyer_id = ?)", Integer.class, BUYER_ID);
    }
}
