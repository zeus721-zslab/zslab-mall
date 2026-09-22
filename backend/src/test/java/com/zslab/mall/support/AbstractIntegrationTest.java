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
 *
 * <p><b>스케줄러 전역 비활성(Track 96-6)</b>: 월 정산 자동 생성·등급 재산정 스케줄러는 운영 데이터를 자동 변경하므로 모든 통합 테스트
 * 컨텍스트에서 킬스위치를 내려 자동 발화를 막는다(개별 클래스 {@code @TestPropertySource} 나열 누락 트랩 방지). 해당 스케줄러 IT는
 * 빈 대신 직접 생성해 호출한다. 기존 스케줄러(auto-cancel 등)는 각 IT의 명시 나열 관례를 유지한다.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MariaDbTestContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", MariaDbTestContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", MariaDbTestContainer.INSTANCE::getPassword);
        registry.add("spring.datasource.driver-class-name", MariaDbTestContainer.INSTANCE::getDriverClassName);
        registry.add("zslab.settlement.monthly-creation.enabled", () -> "false");
        registry.add("zslab.grade.recalculation.enabled", () -> "false");
    }
}
