package com.zslab.mall.stats.service;

import com.zslab.mall.stats.controller.response.AdminSalesBreakdownResponse;
import com.zslab.mall.stats.controller.response.SalesBreakdownRowResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 매출 분해 테이블 CSV 직렬화(Track 87·D-181). RFC 4180 — 구분자 콤마·행 구분 CRLF·콤마/따옴표/개행을 포함한 값은 따옴표로 감싸고
 * 내부 따옴표는 두 번 쓴다. 선두에 UTF-8 BOM(EF BB BF)을 두어 엑셀이 한글을 UTF-8로 읽게 한다.
 * 헤더 행은 한글, 금액·건수는 숫자 그대로(콤마 없음)라 스프레드시트에서 바로 계산된다.
 *
 * <p>{@code byte[]}로 만든다 — 행 수가 축 키 수(상품 수십·셀러·카테고리 한 자리)라 스트리밍({@code StreamingResponseBody})이 필요 없고
 * Content-Length가 확정되는 편이 단순하다(규모 증가 시 재검토).
 */
@Component
public class SalesBreakdownCsvWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String LINE_SEPARATOR = "\r\n";
    private static final String SEPARATOR = ",";
    private static final String QUOTE = "\"";
    private static final String ESCAPED_QUOTE = "\"\"";
    private static final List<String> HEADERS = List.of("키", "이름", "매출", "비중(%)", "주문수", "수량", "비교기간 매출");

    public byte[] write(AdminSalesBreakdownResponse breakdown) {
        StringBuilder body = new StringBuilder();
        appendLine(body, HEADERS);
        for (SalesBreakdownRowResponse row : breakdown.rows()) {
            appendLine(body, List.of(
                    nullToEmpty(row.key()),
                    nullToEmpty(row.name()),
                    String.valueOf(row.revenue()),
                    String.valueOf(row.share()),
                    String.valueOf(row.orderCount()),
                    String.valueOf(row.quantity()),
                    row.compareRevenue() == null ? "" : String.valueOf(row.compareRevenue())));
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(UTF8_BOM);
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream은 IOException을 내지 않지만 시그니처상 필요 — 발생 시 500으로 올린다
            throw new UncheckedIOException("CSV 직렬화 실패", e);
        }
    }

    private static void appendLine(StringBuilder body, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                body.append(SEPARATOR);
            }
            body.append(escape(values.get(index)));
        }
        body.append(LINE_SEPARATOR);
    }

    /** 콤마·따옴표·개행이 있으면 따옴표로 감싸고 내부 따옴표는 두 번(RFC 4180 §2-6·7). */
    private static String escape(String value) {
        boolean needsQuote = value.contains(SEPARATOR) || value.contains(QUOTE) || value.contains("\n")
                || value.contains("\r");
        if (!needsQuote) {
            return value;
        }
        return QUOTE + value.replace(QUOTE, ESCAPED_QUOTE) + QUOTE;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
