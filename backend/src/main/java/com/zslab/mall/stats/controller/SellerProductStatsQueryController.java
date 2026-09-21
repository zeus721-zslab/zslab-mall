package com.zslab.mall.stats.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.stats.controller.response.SellerProductStatsResponse;
import com.zslab.mall.stats.service.SellerProductStatsQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 상품 통계 조회 REST 컨트롤러(Track 90-E-3·D-200·관리자 대응 API 없음·단일 GET). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가
 * hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과·PENDING·TERMINATED 401)는 {@link SellerActorResolver}가 한다.
 *
 * <p>from·to는 yyyy-MM-dd(종료일 포함)·최대 365일·비교/단위 파라미터 없음. 날짜 형식·필수 누락·역전·초과는 400 MALFORMED_REQUEST.
 */
@RestController
@RequestMapping("/api/v1/seller/stats/products")
public class SellerProductStatsQueryController {

    private final SellerProductStatsQueryService sellerProductStatsQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerProductStatsQueryController(SellerProductStatsQueryService sellerProductStatsQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerProductStatsQueryService = sellerProductStatsQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 판매 상위·하위·미판매·재고 회전(기간) + 현재 품절 옵션 수(현재 시점). */
    @GetMapping
    public ResponseEntity<SellerProductStatsResponse> getProductStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerProductStatsQueryService.getProductStats(sellerId, from, to));
    }
}
