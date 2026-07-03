package com.zslab.mall.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordEncoder 빈 등록.
 * AuditingConfig가 @EnableJpaAuditing을 별도 @Configuration으로 분리한 선례와 동일하게
 * 인증 인프라 팩토리를 관심사별로 분리한다. (Track 33)
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
