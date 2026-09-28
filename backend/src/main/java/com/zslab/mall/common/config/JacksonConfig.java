package com.zslab.mall.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 3 기본값 중 spring.jackson.* 속성으로 고정할 수 없는 차이를 Jackson 2 동작으로 맞춘다(D-234).
 * 나머지 고정값은 application.yml spring.jackson(use-jackson2-defaults 등)에 있다.
 */
@Configuration
public class JacksonConfig {

    /**
     * 단일 인자 생성자 탐지 범위. Jackson 3 기본은 NON_PRIVATE이고 Jackson 2는 creator 범위(ANY)를 그대로 썼다.
     * spring.jackson.visibility.scalar-constructor는 Jackson 3.1.5 VisibilityChecker.withVisibility가 이 값을 처리하지 않아 효과가 없다.
     */
    @Bean
    JsonMapperBuilderCustomizer jackson2ScalarConstructorVisibility() {
        return builder -> builder.changeDefaultVisibility(
                visibilityChecker -> visibilityChecker.withScalarConstructorVisibility(Visibility.ANY));
    }
}
