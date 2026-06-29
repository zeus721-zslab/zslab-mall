package com.zslab.mall.common.auth;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.exception.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * {@code X-Seller-Id} 헤더 기반 Seller 액터 식별자 해석 stub(D-92 Q1 α′). 운영 인증 도입 시 교체 대상이다.
 *
 * <p>헤더 누락 → 401({@link UnauthenticatedException})·형식 오류 → 400({@link MalformedRequestException}).
 * BuyerOrderController/BuyerClaimController의 {@code resolveBuyerId} 패턴과 1:1 정합한다(D-39).
 */
@Component
public class HeaderSellerActorResolver implements SellerActorResolver {

    private static final String SELLER_ID_HEADER = "X-Seller-Id";

    @Override
    public Long resolve(HttpServletRequest request) {
        String raw = request.getHeader(SELLER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException("X-Seller-Id 헤더 누락");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("X-Seller-Id 형식 오류");
        }
    }
}
