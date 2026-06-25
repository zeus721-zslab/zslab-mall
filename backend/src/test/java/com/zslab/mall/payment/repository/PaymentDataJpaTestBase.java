package com.zslab.mall.payment.repository;

import com.zslab.mall.common.config.AuditingConfig;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.PaymentMethod;
import java.time.LocalDateTime;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Payment Aggregate @DataJpaTest 공통 베이스(Track B {@code OrderDataJpaTestBase} 패턴 준용).
 *
 * <p>testcontainers MariaDB(11.4 LTS)를 싱글톤 기동하고 Flyway V1·V2·V3를 자동 적용한다.
 * {@link AutoConfigureTestDatabase.Replace#NONE}으로 임베디드 치환을 막아 실 MariaDB 스키마에 대해 매핑·제약을 검증한다.
 *
 * <p><b>FK 체크</b>: payment.order_id FK 상위(order·user 등)는 Track 7/Track B 소관이라 본 트랙에서 영속할 수 없으므로
 * {@link #disableForeignKeyChecks()}로 비활성화한다. UNIQUE·CHECK 제약은 FK와 무관하게 그대로 검증된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditingConfig.class) // @DataJpaTest는 JPA Auditing 미활성 → created_at·updated_at 주입 위해 명시 import
abstract class PaymentDataJpaTestBase {

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

    /** payment 외부 FK(order_id) 검증을 비활성화한다(상위 그래프는 별도 트랙). 영속 직전 호출한다. */
    protected void disableForeignKeyChecks() {
        entityManager.getEntityManager()
                .createNativeQuery("SET FOREIGN_KEY_CHECKS = 0")
                .executeUpdate();
    }

    /** PENDING 결제 1건을 구성한다(미영속). attempt_key는 호출자가 유일하게 지정한다. */
    protected Payment buildPayment(Long orderId, String attemptKey) {
        return Payment.create(orderId, PaymentMethod.CARD, 10_000L, attemptKey, LocalDateTime.now().plusMinutes(30));
    }
}
