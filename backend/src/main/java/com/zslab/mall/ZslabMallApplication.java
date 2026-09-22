package com.zslab.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// D-208: 인증은 JwtAuthenticationFilter가 직접 수행하고 UserDetailsService·AuthenticationManager 소비처가 없으므로
// 기본 InMemoryUserDetailsManager(기동마다 "Using generated security password" 로그) 자동구성을 제외한다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ZslabMallApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZslabMallApplication.class, args);
    }
}
