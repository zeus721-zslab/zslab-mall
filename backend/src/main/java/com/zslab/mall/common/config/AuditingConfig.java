package com.zslab.mall.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 설정. {@code @CreatedDate}·{@code @LastModifiedDate}·{@code @CreatedBy}·{@code @LastModifiedBy}
 * 자동 주입을 켠다. 감사자(created_by·updated_by) 공급원은 {@link AuditorAwareImpl}.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class AuditingConfig {

    @Bean
    public AuditorAware<Long> auditorAware() {
        return new AuditorAwareImpl();
    }
}
