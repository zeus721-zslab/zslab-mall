package com.zslab.mall.stats.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.stats.controller.response.SellerOrderStatsResponse;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.service.SellerOrderStatsQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 주문·클레임 통계 조회 REST 컨트롤러(Track 90-E-2·D-200·관리자 {@code AdminOrderStatsQueryController} 동형·단일 GET·CSV 없음).
 * URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고, 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과·
 * PENDING·TERMINATED 401)는 {@link SellerActorResolver}가 한다 — 요청 파라미터로 셀러를 지정할 수 없다.
 *
 * <p>from·to는 yyyy-MM-dd(종료일 포함)·최대 365일. 허용 외 enum·날짜 형식·필수 누락은 400 MALFORMED_REQUEST(GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1/seller/stats/orders")
public class SellerOrderStatsQueryController {

    private final SellerOrderStatsQueryService sellerOrderStatsQueryService;
    private final SellerActorResolver sellerActorResolver;

    public SellerOrderStatsQueryController(SellerOrderStatsQueryService sellerOrderStatsQueryService,
            SellerActorResolver sellerActorResolver) {
        this.sellerOrderStatsQueryService = sellerOrderStatsQueryService;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 퍼널 + 소요시간 + 클레임 요약·추이·유형/사유/상품별 분포. unit 기본 DAY·compare 기본 NONE. from&gt;to·365일 초과 400. */
    @GetMapping
    public ResponseEntity<SellerOrderStatsResponse> getOrderStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatsUnit unit,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerOrderStatsQueryService.getOrderStats(sellerId, from, to, unit, compare));
    }
}
