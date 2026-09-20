package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.UnauthenticatedException;
import com.zslab.mall.seller.exception.SellerSuspendedException;
import com.zslab.mall.seller.repository.SellerMembershipProjection;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.seller.service.SellerAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * SecurityContext 기반 Seller 액터 식별자 해석(Track 31 Phase 3·Track 36 γ Phase 2·Track 90-A 상태 가드). JwtAuthenticationFilter가
 * 채운 principal(user.id·BIGINT)을 seller_user 매핑으로 seller.id에 단건 해소해 반환한다. user_id 단독 UNIQUE(V12)로
 * user.id→seller.id는 최대 1건이다. Phase 1까지의 {@code X-Seller-Id} 헤더 파싱 stub(D-92 Q1 α′)은 SecurityContext
 * 조회로 대체됐고(클래스명은 레거시 유지·rename 이연), Track 36 γ에서 user.id passthrough 결함을 실 매핑으로 교정했다.
 *
 * <p><b>셀러 상태 가드(Track 90-A·D-187 §8)</b>: 셀러 API 컨트롤러 전부가 이 resolver를 첫 줄에서 호출하므로 여기가 단일 차단점이다.
 * 판정은 {@link SellerAccessPolicy}와 공유한다.
 * <ul>
 *   <li>인증된 액터 없음 · seller_user 매핑 없음 · seller 부재/soft-delete · PENDING/TERMINATED → 401 {@link UnauthenticatedException}
 *       (인증 자체 무효·기존 단일 실패 계약 유지·사유 미구분). 로그인 시점에 허용됐던 셀러가 이후 종료돼도 토큰 만료(1h) 전에 즉시 막힌다.</li>
 *   <li>SUSPENDED + 쓰기 메서드(GET·HEAD 외) → 403 {@link SellerSuspendedException}. SUSPENDED는 유효한 세션 상태이며 해당 행위만
 *       금지되므로 인증 실패(401)가 아니라 인가 거부(403)로 응답한다(조회는 허용·정지 사유 확인 경로 보장).</li>
 * </ul>
 * <b>계약(Track 90-B-1 외부 검토 r2)</b>: 요청 본문 검증({@code @Valid})은 컨트롤러 인자 해소 단계라 이 상태 가드보다 먼저 실행된다. 따라서
 * malformed 요청은 셀러 상태와 무관하게 400이 되고(SUSPENDED도 403이 아닌 400), 쓰기 차단·DB 불변은 400·403 두 경로 모두에서 보장된다.
 * {@code resolve(HttpServletRequest)}의 request 인자는 쓰기 메서드 판정에만 쓴다. 자격증명 형식 오류는 상위
 * {@code JwtAuthenticationFilter}가 401로 선처리하므로 본 resolver의 400 경로는 없다.
 */
@Slf4j
@Component
public class HeaderSellerActorResolver implements SellerActorResolver {

    /** 정지 셀러에도 허용되는 조회 메서드. 그 외(POST·PUT·PATCH·DELETE)는 쓰기로 본다. */
    private static final Set<HttpMethod> READ_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    private final SellerUserRepository sellerUserRepository;

    public HeaderSellerActorResolver(SellerUserRepository sellerUserRepository) {
        this.sellerUserRepository = sellerUserRepository;
    }

    @Override
    public Long resolve(HttpServletRequest request) {
        Long userId = SecurityContextActorSupport.requireActorId();
        SellerMembershipProjection membership = sellerUserRepository.findMembershipByUserId(userId)
                .orElseThrow(() -> new UnauthenticatedException("인증된 판매자를 확인할 수 없습니다"));
        if (!SellerAccessPolicy.isSessionAllowed(membership.getStatus())) {
            // 사유(PENDING·TERMINATED)는 로그로만 구분하고 외부는 매핑 부재와 같은 401로 통합한다.
            log.warn("[Auth] 셀러 상태로 인증 거부 userId={} sellerId={} status={}",
                    userId, membership.getSellerId(), membership.getStatus());
            throw new UnauthenticatedException("인증된 판매자를 확인할 수 없습니다");
        }
        if (!isReadRequest(request) && !SellerAccessPolicy.isWriteAllowed(membership.getStatus())) {
            log.warn("[Auth] 정지 셀러 쓰기 차단 userId={} sellerId={} method={} uri={}",
                    userId, membership.getSellerId(), request.getMethod(), request.getRequestURI());
            throw new SellerSuspendedException("정지된 판매자는 조회만 가능합니다");
        }
        return membership.getSellerId();
    }

    private static boolean isReadRequest(HttpServletRequest request) {
        return READ_METHODS.contains(HttpMethod.valueOf(request.getMethod()));
    }
}
