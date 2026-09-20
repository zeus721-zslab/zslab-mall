package com.zslab.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 제거된 셀러 클레임 처리 매핑 부재 고정 테스트(Track 92·D-196 외부 검토 반영). {@code /api/v1/claims/**}의 POST·PUT·PATCH·DELETE 매핑을
 * 실제 {@link RequestMappingHandlerMapping}에서 수집해 구매자 정당 쓰기 3건과 정확히 일치하는지 단언한다.
 * 감지 범위: (a) 제거된 approve·reject·confirm-pickup·inspect·register-exchange-shipment 부활 (b) 다른 경로명으로의 부활
 * (c) 인가 역할과 무관한 부활 — 매핑이 하나라도 늘면 RED. 인가 계층은 SellerClaimIntegrationTest·SellerDeliveryIntegrationTest의
 * 403 반전 단언이 따로 감시한다.
 *
 * <p>HTTP 응답(404/500)을 기대하는 단언 대신 매핑 존재를 직접 검사하는 이유(LT-27): 필터를 통과하는 역할(BUYER)의 토큰으로 부재 경로를
 * 치면 NoResourceFoundException이 GlobalExceptionHandler의 Exception catch-all에 잡혀 500이 되어 "부재"와 "서버 오류"를 구분할 수 없고,
 * 필터에서 막히는 역할(SELLER)의 403은 매핑이 되살아나도 같은 403이라 부활을 못 본다.
 */
class ClaimProcessingMappingAbsenceTest extends AbstractIntegrationTest {

    private static final String CLAIMS_PREFIX = "/api/v1/claims";
    private static final Set<RequestMethod> WRITE_METHODS =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** 2026-09-21 실측(STEP 711): BuyerClaimController의 클레임 신청·사진 업로드·회수 송장 등록 3건. 갱신은 D-XX 박제와 함께. */
    private static final List<String> ALLOWED_CLAIM_WRITE_MAPPINGS = List.of(
            "POST /api/v1/claims",
            "POST /api/v1/claims/attachments",
            "POST /api/v1/claims/{claimPublicId}/return-shipment");

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    @DisplayName("/api/v1/claims/** POST·PUT·PATCH·DELETE 매핑 집합 = 구매자 쓰기 3건과 정확히 일치(제거된 처리 매핑 부활·경로 변경 부활은 RED)")
    void claimWriteMappings_matchBuyerAllowedListExactly() {
        Set<String> actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            for (String path : info.getPatternValues()) {
                if (!path.equals(CLAIMS_PREFIX) && !path.startsWith(CLAIMS_PREFIX + "/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    if (WRITE_METHODS.contains(method)) {
                        actual.add(method.name() + " " + path);
                    }
                }
            }
        }

        assertThat(actual).containsExactlyInAnyOrderElementsOf(ALLOWED_CLAIM_WRITE_MAPPINGS);
    }
}
