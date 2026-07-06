package com.zslab.mall.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 모든 {@code @SpringBootTest} 슬라이스 통합 테스트의 공통 베이스(Track 63 싱글톤 전환).
 *
 * <p>{@link MariaDbTestContainer#INSTANCE} 싱글톤 컨테이너를 {@code @DynamicPropertySource}로 주입한다.
 * {@code @AutoConfigureMockMvc}는 상위에 두지 않는다 — MockMvc가 필요한 서브클래스만 개별 병기해 슬라이스 부담을 최소화한다.
 * FK_CHECKS 세션 변수 복원은 {@code @SpringBootTest} 계열이 각자 {@code @BeforeEach}/{@code @AfterEach} try-finally로
 * {@code =0}/{@code =1} 짝을 이미 보유하므로 베이스에서 강제하지 않는다({@link AbstractDataJpaTest}와 다른 지점).
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MariaDbTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", MariaDbTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", MariaDbTestContainer.INSTANCE::getPassword);
        registry.add("spring.datasource.driver-class-name", MariaDbTestContainer.INSTANCE::getDriverClassName);
    }
}
