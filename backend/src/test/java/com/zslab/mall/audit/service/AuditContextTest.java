package com.zslab.mall.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditContext} 값객체 검증 — 필수값 가드·팩토리 2종(ip/UA 유무).
 */
class AuditContextTest {

    private static final Long ACTOR_ID = 7L;
    private static final String ACTOR_ROLE = "ADMIN";

    @Test
    @DisplayName("of(actor,role): ip/UA 없이 생성·해당 필드 null")
    void of_withoutIpUa() {
        AuditContext context = AuditContext.of(ACTOR_ID, ACTOR_ROLE);

        assertThat(context.actorUserId()).isEqualTo(ACTOR_ID);
        assertThat(context.actorRole()).isEqualTo(ACTOR_ROLE);
        assertThat(context.ipAddress()).isNull();
        assertThat(context.userAgent()).isNull();
    }

    @Test
    @DisplayName("of(actor,role,ip,ua): 전체 필드 보존")
    void of_withIpUa() {
        AuditContext context = AuditContext.of(ACTOR_ID, ACTOR_ROLE, "127.0.0.1", "JUnit");

        assertThat(context.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(context.userAgent()).isEqualTo("JUnit");
    }

    @Test
    @DisplayName("actorUserId null → IllegalArgumentException")
    void nullActorUserId_throws() {
        assertThatThrownBy(() -> AuditContext.of(null, ACTOR_ROLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("actorRole null·blank → IllegalArgumentException")
    void nullOrBlankActorRole_throws() {
        assertThatThrownBy(() -> AuditContext.of(ACTOR_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditContext.of(ACTOR_ID, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
