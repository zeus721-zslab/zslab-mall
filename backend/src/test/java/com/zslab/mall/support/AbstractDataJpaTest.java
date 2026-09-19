package com.zslab.mall.support;

import com.zslab.mall.common.config.AuditingConfig;
import com.zslab.mall.common.config.BankAccountEncryptionConfig;
import com.zslab.mall.seller.converter.AccountNumberEncryptionConverter;
import com.zslab.mall.seller.migration.V33__Encrypt_seller_bank_account_numbers;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 모든 {@code @DataJpaTest} 슬라이스 테스트의 공통 베이스(Track 63 싱글톤 전환).
 *
 * <p>{@link MariaDbTestContainer#INSTANCE} 싱글톤 컨테이너를 {@code @DynamicPropertySource}로 주입하고,
 * {@link AutoConfigureTestDatabase.Replace#NONE}으로 임베디드 치환을 막아 실 MariaDB 스키마(Flyway 적용)에 대해 매핑을 검증한다.
 * {@code @DataJpaTest}는 JPA Auditing을 켜지 않으므로 {@link AuditingConfig}를 명시 import해 created_at·updated_at을 주입한다.
 *
 * <p>Track 89-F(D-188) 계좌 암호화 빈 3종도 명시 import한다 — (1) {@link AccountNumberEncryptionConverter}는 {@code @Convert}로
 * 엔티티에 묶여 있어 Hibernate가 EntityManagerFactory 부팅 시 SpringBeanContainer로 생성자 주입 인스턴스화를 시도하므로, 슬라이스에
 * {@link BankAccountEncryptionConfig}(키 → 암복호기)가 없으면 계좌 테스트가 아니어도 전 슬라이스가 기동 실패한다. (2) V33 Java
 * 마이그레이션은 Spring 빈으로만 Flyway에 등록되는데, 싱글톤 컨테이너를 {@code @SpringBootTest}가 먼저 V33까지 올린 뒤 슬라이스가
 * V33 없이 Flyway validate를 돌리면 "applied but not resolved" 로 실패한다(실행 순서 의존 flake) → 슬라이스도 같은 빈을 등록한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditingConfig.class, BankAccountEncryptionConfig.class, AccountNumberEncryptionConverter.class,
        V33__Encrypt_seller_bank_account_numbers.class})
public abstract class AbstractDataJpaTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MariaDbTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", MariaDbTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", MariaDbTestContainer.INSTANCE::getPassword);
        registry.add("spring.datasource.driver-class-name", MariaDbTestContainer.INSTANCE::getDriverClassName);
    }

    @Autowired
    protected TestEntityManager entityManager;

    /**
     * 싱글톤 공유 커넥션 풀에서 서브클래스가 남긴 {@code FOREIGN_KEY_CHECKS=0} 세션 변수를 무조건 복원한다(LT-02).
     *
     * <p>{@code @DataJpaTest}는 클래스 트랜잭션을 롤백하지만 {@code SET FOREIGN_KEY_CHECKS}는 세션 변수라 롤백에 씻기지 않고
     * 동일 커넥션이 풀로 반납될 때 다음 테스트로 누수된다. JUnit5는 상위 클래스 {@code @AfterEach}를 서브클래스 것보다 나중에
     * 실행하므로, 여기서 최후에 {@code =1}로 복원해 누수를 차단한다.
     *
     * <p><b>doWork를 쓰는 이유</b>: {@code createNativeQuery(...).executeUpdate()}는 실행 직전 영속성 컨텍스트를
     * autoflush한다. 제약 위반(UNIQUE·FK·CHECK)을 기대하는 테스트는 예외 후 세션이 "don't flush after exception" 상태라,
     * 그 autoflush가 {@code org.hibernate.AssertionFailure}로 터지며 복원도 중단돼 FK=0이 공유 풀로 누수된다.
     * {@link org.hibernate.Session#doWork}는 바인딩된 동일 커넥션에 raw JDBC를 실행해 flush를 우회하므로,
     * FK를 끄지 않은 테스트에도 진짜 no-op·무해하다. (createNativeQuery 방식으로 되돌리지 말 것.)
     *
     * <p>접근 제어자를 두지 않는다(package-private): 일부 서브클래스가 이미 동일 이름 {@code restoreForeignKeyChecks()}를
     * 보유해 {@code protected}로 승격 시 "약한 접근 오버라이드 불가" 컴파일 에러가 난다. 서로 다른 패키지의 package-private은
     * 오버라이드 관계가 아니므로 충돌 없이 공존하며, JUnit5는 계층 순회로 본 메서드를 여전히 호출한다.
     */
    @AfterEach
    void restoreForeignKeyChecks() {
        entityManager.getEntityManager().unwrap(org.hibernate.Session.class)
                .doWork(connection -> {
                    try (java.sql.Statement statement = connection.createStatement()) {
                        statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                    }
                });
    }
}
