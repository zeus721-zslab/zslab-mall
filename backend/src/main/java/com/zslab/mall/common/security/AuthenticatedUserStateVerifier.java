package com.zslab.mall.common.security;

import com.zslab.mall.user.entity.UserAuthState;
import com.zslab.mall.user.repository.UserAuthStateRepository;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * 서명·만료 검증을 통과한 토큰의 주체가 아직 유효한 회원인지 DB 상태로 재확인한다(Track 84·무상태 JWT의 즉시 무효화 수단).
 * BUYER·SELLER·ADMIN 모든 액터는 actorId=user.id({@code DbRoleAuthorization} 판정 기준)라 역할 무관하게 적용된다.
 *
 * <ul>
 *   <li>soft-delete(deleted_at)·탈퇴(withdrawn_at) 회원 → 거부</li>
 *   <li>credentials_changed_at의 epoch 초보다 <b>이전 초</b>에 발급된 토큰(iat 초 &lt; 갱신 시각 초) → 거부. JWT iat는 초 정밀도라
 *       <b>같은 epoch 초에 발급된 토큰은 유효</b>하다(최대 1초 중첩 허용·의도된 정책).</li>
 *   <li>user 행 자체가 없으면 통과 — 토큰은 DB 조회 후에만 발급되고 회원은 물리 삭제되지 않으므로 실환경에선 도달하지 않으며,
 *       통합 테스트 다수가 user 행 없는 임의 actorId로 토큰을 발급하는 픽스처 관례를 유지하기 위한 선택이다.
 *       <b>전제 불변식: user 물리 삭제 금지</b>(soft-delete만·FK RESTRICT). 이 불변식이 깨지면 삭제된 id의 토큰이 통과한다.</li>
 * </ul>
 *
 * <p>시각 비교: JVM TZ와 hibernate.jdbc.time_zone이 모두 Asia/Seoul이라 {@code LocalDateTime.now()}로 저장한 값이 같은 벽시계로
 * 돌아온다. iat는 epoch 초이므로 갱신 시각을 {@link ZoneId#systemDefault()}(= 저장 시 now()의 기준 zone)로 epoch 초 변환해 비교한다.
 * 거부는 {@link BadCredentialsException}으로 던져 기존 토큰 검증 실패와 같은 401(UNAUTHENTICATED) 경로를 탄다.
 */
@Slf4j
@Component
public class AuthenticatedUserStateVerifier {

    private final UserAuthStateRepository userAuthStateRepository;

    public AuthenticatedUserStateVerifier(UserAuthStateRepository userAuthStateRepository) {
        this.userAuthStateRepository = userAuthStateRepository;
    }

    /**
     * @throws BadCredentialsException 삭제·탈퇴 회원이거나 자격증명 갱신 이전에 발급된 토큰인 경우
     */
    public void verify(TokenPayload payload) {
        UserAuthState state = userAuthStateRepository.findById(payload.actorId()).orElse(null);
        if (state == null) {
            return;
        }
        if (state.getDeletedAt() != null || state.getWithdrawnAt() != null) {
            log.warn("[Auth] 삭제·탈퇴 회원 토큰 거부 actorId={}", payload.actorId());
            throw new BadCredentialsException("유효하지 않은 인증 토큰");
        }
        if (state.getCredentialsChangedAt() != null) {
            long changedAtSeconds = state.getCredentialsChangedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
            if (payload.issuedAt().getEpochSecond() < changedAtSeconds) {
                log.warn("[Auth] 자격증명 갱신 이전 발급 토큰 거부 actorId={}", payload.actorId());
                throw new BadCredentialsException("유효하지 않은 인증 토큰");
            }
        }
    }
}
