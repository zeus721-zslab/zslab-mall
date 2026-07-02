package com.zslab.mall.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화 설정(Track 25·D-08 M-14). {@code @Scheduled} 배치의 최초 진입점이며 만료 결제 자동 정리
 * ({@link com.zslab.mall.payment.scheduler.ExpirePaymentScheduler})가 이를 통해 구동된다.
 *
 * <p>{@link AuditingConfig}가 {@code @EnableJpaAuditing}을 별도 {@code @Configuration}으로 분리한 선례와 동일하게,
 * 향후 배치 스케줄러(NotificationLog 재시도 등)의 공유 진입점 역할을 한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
