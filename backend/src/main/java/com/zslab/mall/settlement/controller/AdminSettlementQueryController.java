package com.zslab.mall.settlement.controller;

import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.settlement.controller.response.AdminSettlementDetailResponse;
import com.zslab.mall.settlement.controller.response.AdminSettlementListResponse;
import com.zslab.mall.settlement.controller.response.AdminSettlementSummaryResponse;
import com.zslab.mall.settlement.controller.response.SettlementItemResponse;
import com.zslab.mall.settlement.enums.SettlementItemType;
import com.zslab.mall.settlement.enums.SettlementStatus;
import com.zslab.mall.settlement.service.AdminSettlementQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 정산 조회 REST 컨트롤러(Track 85). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제한다. HTTP 책임만
 * 가진다(파라미터 바인딩·위임). 쓰기(생성·재생성·전이)는 {@link AdminSettlementController}.
 */
@RestController
public class AdminSettlementQueryController {

    private final AdminSettlementQueryService adminSettlementQueryService;

    public AdminSettlementQueryController(AdminSettlementQueryService adminSettlementQueryService) {
        this.adminSettlementQueryService = adminSettlementQueryService;
    }

    /** 월별 정산 목록. year·month 필수(누락 400)·status·keyword(셀러 상호) 선택. 합계는 해당 월 전체. */
    @GetMapping("/api/v1/admin/settlements")
    public ResponseEntity<AdminSettlementListResponse> list(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminSettlementQueryService.list(year, month, status, keyword, page, size));
    }

    /** 정산 상세(셀러 연락처 마스킹·계좌 끝 4자리). 미존재 404. */
    @GetMapping("/api/v1/admin/settlements/{id}")
    public ResponseEntity<AdminSettlementDetailResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(adminSettlementQueryService.get(id));
    }

    /** 정산 품목 스냅샷(type SALE|REFUND 선택·occurred_at 오름차순). 미존재 404. */
    @GetMapping("/api/v1/admin/settlements/{id}/items")
    public ResponseEntity<PagedResponse<SettlementItemResponse>> items(
            @PathVariable Long id,
            @RequestParam(required = false) SettlementItemType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminSettlementQueryService.listItems(id, type, page, size));
    }

    /** 셀러 월별 정산 이력(최신 기간순). 셀러 식별자는 public_id(slr_)·미존재 404. */
    @GetMapping("/api/v1/admin/sellers/{sellerPublicId}/settlements")
    public ResponseEntity<PagedResponse<AdminSettlementSummaryResponse>> listBySeller(
            @PathVariable String sellerPublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminSettlementQueryService.listBySeller(sellerPublicId, page, size));
    }
}
