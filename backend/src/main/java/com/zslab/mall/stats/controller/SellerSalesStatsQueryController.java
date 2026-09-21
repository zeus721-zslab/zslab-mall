package com.zslab.mall.stats.controller;

import com.zslab.mall.common.auth.SellerActorResolver;
import com.zslab.mall.stats.controller.response.SellerSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.SellerSalesStatsResponse;
import com.zslab.mall.stats.enums.SellerStatsAxis;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.service.SellerSalesBreakdownCsvWriter;
import com.zslab.mall.stats.service.SellerSalesStatsQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * 셀러 매출 통계 조회 REST 컨트롤러(Track 90-E-1·D-200). URL prefix {@code /api/v1/seller/**}는 SecurityConfig가 hasRole(SELLER)로 강제하고,
 * 셀러 식별·상태 가드(D-190·GET이라 SUSPENDED 통과·PENDING·TERMINATED 401)는 {@link SellerActorResolver}가 한다 — 요청 파라미터로
 * 셀러를 지정할 수 없다. 파라미터·응답 형태는 관리자 {@code AdminSalesStatsQueryController}를 따르며 축만 {@link SellerStatsAxis}다.
 *
 * <p>from·to는 yyyy-MM-dd(종료일 포함)·최대 365일. 허용 외 enum·날짜 형식은 400 MALFORMED_REQUEST(GlobalExceptionHandler), 필수 누락도 400.
 */
@RestController
@RequestMapping("/api/v1/seller/stats/sales")
public class SellerSalesStatsQueryController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final Map<SellerStatsAxis, String> AXIS_FILE_LABELS = Map.of(
            SellerStatsAxis.PRODUCT, "상품", SellerStatsAxis.OPTION, "옵션", SellerStatsAxis.CATEGORY, "카테고리");

    private final SellerSalesStatsQueryService sellerSalesStatsQueryService;
    private final SellerSalesBreakdownCsvWriter sellerSalesBreakdownCsvWriter;
    private final SellerActorResolver sellerActorResolver;

    public SellerSalesStatsQueryController(SellerSalesStatsQueryService sellerSalesStatsQueryService,
            SellerSalesBreakdownCsvWriter sellerSalesBreakdownCsvWriter, SellerActorResolver sellerActorResolver) {
        this.sellerSalesStatsQueryService = sellerSalesStatsQueryService;
        this.sellerSalesBreakdownCsvWriter = sellerSalesBreakdownCsvWriter;
        this.sellerActorResolver = sellerActorResolver;
    }

    /** 요약 + 추이. unit 기본 DAY·compare 기본 NONE. from&gt;to·365일 초과 400. */
    @GetMapping
    public ResponseEntity<SellerSalesStatsResponse> getSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatsUnit unit,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerSalesStatsQueryService.getSales(sellerId, from, to, unit, compare));
    }

    /** 분해 테이블(매출 내림차순·전량). axis = PRODUCT·OPTION·CATEGORY. */
    @GetMapping("/breakdown")
    public ResponseEntity<SellerSalesBreakdownResponse> getBreakdown(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            @RequestParam SellerStatsAxis axis,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        return ResponseEntity.ok(sellerSalesStatsQueryService.getBreakdown(sellerId, from, to, compare, axis));
    }

    /**
     * 분해 테이블 CSV 내보내기(파라미터·행 집합은 {@link #getBreakdown}과 동일·관리자 D-181 α 관례). {@code text/csv; charset=UTF-8}·BOM 선두·
     * {@code Content-Disposition: attachment}에 ASCII filename과 한글 filename*(RFC 5987 UTF-8 percent-encoding)을 함께 둔다.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBreakdownCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            @RequestParam SellerStatsAxis axis,
            HttpServletRequest request) {
        Long sellerId = sellerActorResolver.resolve(request);
        SellerSalesBreakdownResponse breakdown = sellerSalesStatsQueryService.getBreakdown(sellerId, from, to, compare, axis);
        String asciiFileName = "seller-sales-breakdown-" + axis.name().toLowerCase() + "-" + from + "_" + to + ".csv";
        String koreanFileName = "매출통계_" + AXIS_FILE_LABELS.get(axis) + "_" + from + "_" + to + ".csv";
        String contentDisposition = "attachment; filename=\"" + asciiFileName + "\"; filename*=UTF-8''"
                + UriUtils.encode(koreanFileName, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(sellerSalesBreakdownCsvWriter.write(breakdown));
    }
}
