package com.zslab.mall.stats.controller;

import com.zslab.mall.stats.controller.response.AdminSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.AdminSalesStatsResponse;
import com.zslab.mall.stats.enums.StatsAxis;
import com.zslab.mall.stats.enums.StatsCompare;
import com.zslab.mall.stats.enums.StatsUnit;
import com.zslab.mall.stats.service.AdminSalesStatsQueryService;
import com.zslab.mall.stats.service.SalesBreakdownCsvWriter;
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
 * 관리자 매출 통계 조회 REST 컨트롤러(Track 87·D-181). 인가는 SecurityConfig {@code /api/v1/admin/**}→hasRole(ADMIN)이 강제하므로
 * 메서드 @PreAuthorize를 두지 않는다({@code AdminDashboardQueryController} 선례). 바인딩·위임만 담당한다.
 *
 * <p>from·to는 yyyy-MM-dd(종료일 포함)이며 서비스가 KST 반구간으로 바꾼다. 허용 외 enum·날짜 형식은
 * {@code MethodArgumentTypeMismatchException}→400 MALFORMED_REQUEST(GlobalExceptionHandler), 필수 파라미터 누락도 400.
 */
@RestController
@RequestMapping("/api/v1/admin/stats/sales")
public class AdminSalesStatsQueryController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final Map<StatsAxis, String> AXIS_FILE_LABELS = Map.of(
            StatsAxis.CATEGORY, "카테고리", StatsAxis.SELLER, "셀러", StatsAxis.PRODUCT, "상품");

    private final AdminSalesStatsQueryService adminSalesStatsQueryService;
    private final SalesBreakdownCsvWriter salesBreakdownCsvWriter;

    public AdminSalesStatsQueryController(AdminSalesStatsQueryService adminSalesStatsQueryService,
            SalesBreakdownCsvWriter salesBreakdownCsvWriter) {
        this.adminSalesStatsQueryService = adminSalesStatsQueryService;
        this.salesBreakdownCsvWriter = salesBreakdownCsvWriter;
    }

    /** 요약 + 추이. unit 기본 DAY·compare 기본 NONE. from&gt;to 400. */
    @GetMapping
    public ResponseEntity<AdminSalesStatsResponse> getSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAY") StatsUnit unit,
            @RequestParam(defaultValue = "NONE") StatsCompare compare) {
        return ResponseEntity.ok(adminSalesStatsQueryService.getSales(from, to, unit, compare));
    }

    /** 분해 테이블(매출 내림차순·전량). parentKey는 CATEGORY(categoryId)·SELLER(public_id) 드릴다운용이며 PRODUCT에 주면 400. */
    @GetMapping("/breakdown")
    public ResponseEntity<AdminSalesBreakdownResponse> getBreakdown(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            @RequestParam StatsAxis axis,
            @RequestParam(required = false) String parentKey) {
        return ResponseEntity.ok(adminSalesStatsQueryService.getBreakdown(from, to, compare, axis, parentKey));
    }

    /**
     * 분해 테이블 CSV 내보내기(파라미터·행 집합은 {@link #getBreakdown}과 동일·D-181 α). {@code text/csv; charset=UTF-8}·BOM 선두·
     * {@code Content-Disposition: attachment}에 ASCII filename과 한글 filename*(RFC 5987 UTF-8 percent-encoding)을 함께 둔다.
     * 경로 {@code breakdown.csv}는 {@code /breakdown}과 별개 리터럴 세그먼트(Spring 6 PathPattern·접미사 매칭 없음)라 충돌하지 않는다.
     */
    @GetMapping("/breakdown.csv")
    public ResponseEntity<byte[]> exportBreakdownCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "NONE") StatsCompare compare,
            @RequestParam StatsAxis axis,
            @RequestParam(required = false) String parentKey) {
        AdminSalesBreakdownResponse breakdown = adminSalesStatsQueryService.getBreakdown(from, to, compare, axis, parentKey);
        String asciiFileName = "sales-breakdown-" + axis.name().toLowerCase() + "-" + from + "_" + to + ".csv";
        String koreanFileName = "매출통계_" + AXIS_FILE_LABELS.get(axis) + "_" + from + "_" + to + ".csv";
        String contentDisposition = "attachment; filename=\"" + asciiFileName + "\"; filename*=UTF-8''"
                + UriUtils.encode(koreanFileName, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(salesBreakdownCsvWriter.write(breakdown));
    }
}
