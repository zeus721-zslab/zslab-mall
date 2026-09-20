package com.zslab.mall.dashboard.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.dashboard.controller.response.SellerDashboardResponse;
import com.zslab.mall.dashboard.service.SellerDashboardQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 대시보드 조회 REST 컨트롤러(Track 90-B-2). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고,
 * 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과·PENDING·TERMINATED 401)는 {@link SellerActorResolver}가 한다.
 */
@RestController
public class SellerDashboardQueryController {

    private final SellerDashboardQueryService sellerDashboardQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerDashboardQueryController(SellerDashboardQueryService sellerDashboardQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerDashboardQueryService = sellerDashboardQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /**
     * 셀러 대시보드 전체(기간 요약·처리 대기 4·일별 추이·최근 품목/클레임 5·상위 상품 5) 실시간 집계. from/to는 KST 일(ISO-8601 date·
     * 양끝 포함)·기본 최근 30일·최대 92일. 매출·환불은 자기 품목(order_item) 기준이며 summary.orderCount는 자기 품목이 포함된 주문 수라
     * {@code /seller/order-items}의 totalCount(품목 행 수)와 다를 수 있다(한 주문에 여러 품목). from&gt;to·92일 초과 400.
     */
    @GetMapping("/api/v1/seller/dashboard")
    public ResponseEntity<SellerDashboardResponse> get(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerDashboardQueryService.getDashboard(sellerId, from, to));
    }
}
