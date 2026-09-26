package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.common.exception.DemoAccountProtectedException;
import com.zslab.mall.user.entity.User;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * {@link DemoAccountGuard} 단위 테스트(D-230). 설정 파싱(쉼표·공백·빈 항목·대소문자), 빈 설정 = 보호 없음(local·test),
 * 운영(require=true) 빈 설정 = 기동 실패(fail-open 차단)와 application-prod.yml이 실제로 require=true를 켜는지 확인한다.
 */
class DemoAccountGuardTest {

    private static final String NAME = "데모";
    private static final String PHONE = "010-1111-2222";
    private static final boolean LOCAL_DEFAULT = false;
    private static final boolean PROD_REQUIRED = true;

    @Test
    @DisplayName("설정 비어 있음(빈 문자열·빈 항목만) · require=false(local·test) → 어떤 계정도 보호하지 않음")
    void blankSetting_protectsNothing() {
        User anyUser = User.create("demo@zslab.test", NAME, PHONE);

        assertThatCode(() -> new DemoAccountGuard("", LOCAL_DEFAULT).requireNotProtected(anyUser)).doesNotThrowAnyException();
        assertThatCode(() -> new DemoAccountGuard(" , ,", LOCAL_DEFAULT).requireNotProtected(anyUser)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fail-open 차단: require=true(prod)인데 보호 계정 0개(빈 값·compose가 만드는 \",,\") → 생성 실패 · 메시지에 이메일 없음 / 1개 이상이면 정상")
    void prodRequired_emptySetting_failsStartup() {
        assertThatThrownBy(() -> new DemoAccountGuard("", PROD_REQUIRED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("@");
        assertThatThrownBy(() -> new DemoAccountGuard(",,", PROD_REQUIRED)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DemoAccountGuard(",demo@zslab.test,", PROD_REQUIRED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("application-prod.yml은 require-protected-accounts=true · 기본 application.yml은 false")
    void prodProfile_enablesRequirement() {
        assertThat(loadYaml("application-prod.yml").getProperty("zslab.demo.require-protected-accounts")).isEqualTo("true");
        assertThat(loadYaml("application.yml").getProperty("zslab.demo.require-protected-accounts")).isEqualTo("false");
    }

    @Test
    @DisplayName("쉼표 구분·앞뒤 공백·대소문자 무시로 일치하면 403 예외, 다른 이메일·이메일 없음은 통과")
    void configuredEmails_matchCaseInsensitively() {
        DemoAccountGuard guard = new DemoAccountGuard(" Admin@ZSLAB.test , demo@zslab.test,", LOCAL_DEFAULT);

        assertThatThrownBy(() -> guard.requireNotProtected(User.create("admin@zslab.test", NAME, PHONE)))
                .isInstanceOf(DemoAccountProtectedException.class)
                .hasMessage("데모 계정은 이 기능을 사용할 수 없습니다.");
        assertThatThrownBy(() -> guard.requireNotProtected(User.create("DEMO@ZSLAB.TEST", NAME, PHONE)))
                .isInstanceOf(DemoAccountProtectedException.class);
        assertThatCode(() -> guard.requireNotProtected(User.create("other@zslab.test", NAME, PHONE))).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNotProtected(User.create(null, NAME, PHONE))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("셀러 해지용 구성원 판정: 보호 계정이 한 명이라도 있으면 403 예외 · 없거나 빈 목록이면 통과")
    void requireNoProtectedMember() {
        DemoAccountGuard guard = new DemoAccountGuard("seller-demo@zslab.test", LOCAL_DEFAULT);
        User normal = User.create("owner@zslab.test", NAME, PHONE);

        assertThatThrownBy(() -> guard.requireNoProtectedMember(List.of(normal, User.create("Seller-Demo@zslab.test", NAME, PHONE))))
                .isInstanceOf(DemoAccountProtectedException.class);
        assertThatCode(() -> guard.requireNoProtectedMember(List.of(normal))).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireNoProtectedMember(List.of())).doesNotThrowAnyException();
    }

    private static Properties loadYaml(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        return factory.getObject();
    }
}
