package com.zslab.mall.dashboard.controller;

import com.zslab.mall.dashboard.controller.response.AdminDashboardResponse;
import com.zslab.mall.dashboard.service.AdminDashboardQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 대시보드 조회 REST 컨트롤러(Track 86·D-180). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제하므로
 * 메서드 @PreAuthorize를 두지 않는다({@code AdminSettlementQueryController} 선례). 파라미터 없는 단일 GET·위임만 담당한다.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardQueryController {

    private final AdminDashboardQueryService adminDashboardQueryService;

    public AdminDashboardQueryController(AdminDashboardQueryService adminDashboardQueryService) {
        this.adminDashboardQueryService = adminDashboardQueryService;
    }

    /** 대시보드 전체(요약·처리 대기·6개월 추이·30일 일별·최근 주문/클레임 5·상위 셀러/상품 5) 실시간 집계. */
    @GetMapping
    public ResponseEntity<AdminDashboardResponse> get() {
        return ResponseEntity.ok(adminDashboardQueryService.getDashboard());
    }
}
