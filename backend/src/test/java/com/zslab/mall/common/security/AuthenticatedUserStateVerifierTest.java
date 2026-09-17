package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.zslab.mall.user.entity.UserAuthState;
import com.zslab.mall.user.repository.UserAuthStateRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 토큰 주체 상태 재확인 단위 테스트(Track 84). 행 없음 통과·삭제/탈퇴 거부·자격증명 갱신 시각 대비 iat 초 단위 비교(경계 포함)를 검증한다.
 * {@link UserAuthState}는 불변 읽기 모델이라 setter가 없어 ReflectionTestUtils로 필드를 채운다.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticatedUserStateVerifierTest {

    private static final long USER_ID = 42L;
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 9, 17, 12, 0, 0, 500_000_000);

    @Mock
    private UserAuthStateRepository userAuthStateRepository;

    @Test
    @DisplayName("user 행이 없으면 통과(통합 테스트 픽스처 관례·실환경 미도달)")
    void missingRow_passes() {
        when(userAuthStateRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> verifier().verify(payload(Instant.now()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("withdrawn_at 또는 deleted_at 마킹 회원 → BadCredentials(401 경로)")
    void withdrawnOrDeleted_rejected() {
        when(userAuthStateRepository.findById(USER_ID)).thenReturn(Optional.of(state(CHANGED_AT, null, null)));
        assertThatThrownBy(() -> verifier().verify(payload(Instant.now()))).isInstanceOf(BadCredentialsException.class);

        when(userAuthStateRepository.findById(USER_ID)).thenReturn(Optional.of(state(null, CHANGED_AT, null)));
        assertThatThrownBy(() -> verifier().verify(payload(Instant.now()))).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("credentials_changed_at 이전 초에 발급된 토큰 → 거부 / 같은 초·이후 초 발급 → 통과(초 단위 절삭 비교)")
    void issuedBeforeCredentialsChange_rejected_sameOrAfterSecond_passes() {
        when(userAuthStateRepository.findById(USER_ID)).thenReturn(Optional.of(state(null, null, CHANGED_AT)));
        long changedSecond = CHANGED_AT.atZone(ZoneId.systemDefault()).toEpochSecond();

        assertThatThrownBy(() -> verifier().verify(payload(Instant.ofEpochSecond(changedSecond - 1))))
                .isInstanceOf(BadCredentialsException.class);
        assertThatCode(() -> verifier().verify(payload(Instant.ofEpochSecond(changedSecond)))).doesNotThrowAnyException();
        assertThatCode(() -> verifier().verify(payload(Instant.ofEpochSecond(changedSecond + 1)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("credentials_changed_at NULL(갱신 이력 없음) → 발급 시각 무관 통과")
    void noCredentialsChange_passes() {
        when(userAuthStateRepository.findById(USER_ID)).thenReturn(Optional.of(state(null, null, null)));

        assertThatCode(() -> verifier().verify(payload(Instant.ofEpochSecond(0)))).doesNotThrowAnyException();
    }

    private AuthenticatedUserStateVerifier verifier() {
        return new AuthenticatedUserStateVerifier(userAuthStateRepository);
    }

    private static TokenPayload payload(Instant issuedAt) {
        return new TokenPayload(USER_ID, ActorRole.BUYER, issuedAt);
    }

    private static UserAuthState state(LocalDateTime withdrawnAt, LocalDateTime deletedAt, LocalDateTime credentialsChangedAt) {
        UserAuthState state = instantiate();
        ReflectionTestUtils.setField(state, "id", USER_ID);
        ReflectionTestUtils.setField(state, "withdrawnAt", withdrawnAt);
        ReflectionTestUtils.setField(state, "deletedAt", deletedAt);
        ReflectionTestUtils.setField(state, "credentialsChangedAt", credentialsChangedAt);
        return state;
    }

    private static UserAuthState instantiate() {
        try {
            java.lang.reflect.Constructor<UserAuthState> constructor = UserAuthState.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("UserAuthState 인스턴스 생성 실패", exception);
        }
    }
}
