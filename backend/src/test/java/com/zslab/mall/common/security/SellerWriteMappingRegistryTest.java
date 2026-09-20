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
 * 셀러 쓰기 매핑 집합 고정 테스트(Track 92·D-196). "셀러는 조회만·처리는 관리자"가 확정이므로 {@code /api/v1/seller/**}의
 * POST·PUT·PATCH·DELETE 매핑을 실제 {@link RequestMappingHandlerMapping}에서 수집해 허용 목록과 정확히 일치하는지 단언한다.
 * 셀러 쓰기 endpoint가 새로 생기거나 사라지면 RED가 나므로, 의도적 추가(허용 목록 갱신)인지 인가 구멍인지 검토하게 만든다.
 */
class SellerWriteMappingRegistryTest extends AbstractIntegrationTest {

    private static final String SELLER_PREFIX = "/api/v1/seller/";
    private static final Set<RequestMethod> WRITE_METHODS =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** 2026-09-21 실측 허용 목록(상품 등록·수정·이미지, 재고 입출고, 파일 업로드, 송장 정정). 갱신은 D-XX 박제와 함께. */
    private static final List<String> ALLOWED_SELLER_WRITE_MAPPINGS = List.of(
            "DELETE /api/v1/seller/products/{productId}/images/{imageId}",
            "PATCH /api/v1/seller/deliveries/{deliveryPublicId}/tracking",
            "PATCH /api/v1/seller/products/{productId}/images/reorder",
            "PATCH /api/v1/seller/products/{productId}/images/{imageId}/main",
            "POST /api/v1/seller/files/images",
            "POST /api/v1/seller/inventories/{variantPublicId}/mark-inbound",
            "POST /api/v1/seller/inventories/{variantPublicId}/mark-outbound",
            "POST /api/v1/seller/products",
            "POST /api/v1/seller/products/{productId}/images",
            "PUT /api/v1/seller/products/{productPublicId}",
            "PUT /api/v1/seller/products/{productPublicId}/images",
            "PUT /api/v1/seller/products/{productPublicId}/variants");

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    @DisplayName("/api/v1/seller/** POST·PUT·PATCH·DELETE 매핑 집합 = 허용 목록 12건과 정확히 일치(신규 셀러 쓰기는 RED)")
    void sellerWriteMappings_matchAllowedListExactly() {
        Set<String> actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            for (String path : info.getPatternValues()) {
                if (!path.startsWith(SELLER_PREFIX)) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    if (WRITE_METHODS.contains(method)) {
                        actual.add(method.name() + " " + path);
                    }
                }
            }
        }

        assertThat(actual).containsExactlyInAnyOrderElementsOf(ALLOWED_SELLER_WRITE_MAPPINGS);
    }
}
