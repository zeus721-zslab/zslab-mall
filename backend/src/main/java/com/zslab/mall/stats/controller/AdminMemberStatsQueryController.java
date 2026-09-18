package com.zslab.mall.stats.controller;

import com.zslab.mall.stats.controller.response.AdminMemberStatsResponse;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.service.AdminMemberStatsQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 통계 조회 REST 컨트롤러(Track 88·D-182). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제하므로
 * 메서드 @PreAuthorize를 두지 않는다. 파라미터 규약(from·to yyyy-MM-dd 종료일 포함·unit·compare·허용 외 값 400)은 매출 통계와 같다.
 */
@RestController
@RequestMapping("/api/v1/admin/stats/members")
public class AdminMemberStatsQueryController {

    private final AdminMemberStatsQueryService adminMemberStatsQueryService;

    public AdminMemberStatsQueryController(AdminMemberStatsQueryService adminMemberStatsQueryService) {
        this.adminMemberStatsQueryService = adminMemberStatsQueryService;
    }

    /** 요약 + 가입 추이 + 등급 분포 + 구매자 분리 + 상위 회원. unit 기본 DAY·compare 기본 NONE. from&gt;to 400. */
    @GetMapping
    public ResponseEntity<AdminMemberStatsResponse> getMemberStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatsUnit unit,
            @RequestParam(defaultValue = "NONE") StatsCompare compare) {
        return ResponseEntity.ok(adminMemberStatsQueryService.getMemberStats(from, to, unit, compare));
    }
}
