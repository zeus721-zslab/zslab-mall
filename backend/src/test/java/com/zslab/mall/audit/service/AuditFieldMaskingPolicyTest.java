package com.zslab.mall.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 감사 적재 필드 ↔ 마스킹 정책 고정 테스트(Track 101-A 외부 검토 반영).
 *
 * <p><b>왜 필요한가</b>: {@link Masker}는 <b>필드명 정확 매칭</b>으로만 민감정보를 가린다(AUD-2). 그래서 새 감사 소비처가
 * 민감한 값을 새 이름으로 실어 보내면 아무도 모르게 평문으로 적재된다 — 마스킹이 "돌고 있다"는 사실만으로는 안전이 보장되지 않고,
 * <b>적재되는 필드 집합</b>과 정책이 함께 관리돼야 한다. 이 테스트는 현재 적재되는 필드 집합을 박제해 두고,
 * 필드가 하나라도 늘거나 줄면 먼저 깨진다. 깨지면 새 필드가 민감정보인지 판단해 {@link Masker}에 등록하거나
 * 아래 {@link #RECORDED_AUDIT_FIELDS}에 추가하는 것이 처치다.
 *
 * <p><b>조회 계층은 검사 대상이 아니다</b>: 마스킹은 적재 시점에 한 번만 걸린다({@code AuditRecorder.record}). 조회
 * ({@code AdminAuditLogQueryService})는 저장된 {@code diff_json}을 그대로 옮기므로, 적재 시점 집합만 지키면 일관성이 유지된다.
 */
class AuditFieldMaskingPolicyTest {

    /** 소스 스캔 루트(Gradle 테스트 작업 디렉터리 = backend/). */
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java", "com", "zslab", "mall");

    /**
     * 2026-09-23 실측 61개: 감사 적재 호출부가 있는 main 소스의 diff 맵 키 전부(Track 101-A 신설분만이 아니라 정산·상품·
     * 셀러·회원·등급 등 <b>기존 소비처까지</b> 포함한다 — 정책은 필드 집합 전체에 걸리므로 부분만 박제하면 의미가 없다).
     * 갱신은 D-XX 박제와 함께 한다(필드를 늘린 트랙이 민감도를 판단했다는 기록이 남아야 한다).
     */
    private static final Set<String> RECORDED_AUDIT_FIELDS = Set.of(
            "accountHolder", "accountNumber", "accountNumberSuffix", "bankCode",
            "basePrice", "businessNo", "carrier", "categoryId",
            "ceoName", "claimId", "commissionRate", "companyName",
            "contactEmail", "contactPhone", "deleted", "displayName",
            "feeAmount", "gradeId", "gradeLockedUntil", "gradeSource",
            "grossAmount", "imageCount", "inspectionResult", "isPrimary",
            "name", "netAmount", "newUserCreated", "ownerUserId",
            "passwordChangeRequired", "passwordHash", "periodEnd", "periodStart",
            "phone", "pickedUpAt", "previousPrimaryBankAccountIds", "quantityOnHand",
            "reason", "reasonCode", "reasonDetail", "refundAmount",
            "rejectMemo", "rejectReasonCode", "reshipCarrier", "reshipTrackingNo",
            "restock", "role", "roleCode", "saleEndAt",
            "saleStartAt", "saleStopSource", "sellerId", "sellerPublicId",
            "soldoutManual", "sortOrder", "status", "supplyPrice",
            "thumbnailUrl", "userId", "userPublicId", "variantCount",
            "withdrawnAt");

    /**
     * 위 집합 중 {@link Masker}가 실제로 가리는 필드(2026-09-23 실측 2개). 나머지 59개는 평문으로 적재된다 —
     * 상태·사유·수량·금액처럼 운영자가 추적하려고 남기는 값이라 가리면 감사가 쓸모없어진다.
     * {@code accountNumberSuffix}는 뒷자리만 담는 별도 필드라 의도적으로 가리지 않는다(정책 목록에 없음).
     */
    private static final Set<String> MASKED_AUDIT_FIELDS = Set.of("accountNumber", "passwordHash");

    private final Masker masker = new Masker();
    private final DiffBuilder diffBuilder = new DiffBuilder(new ObjectMapper());

    @Test
    @DisplayName("적재 필드 집합이 박제와 정확히 일치한다(새 감사 필드가 생기면 먼저 깨진다)")
    void recordedAuditFields_matchPinnedSet() throws IOException {
        Set<String> scanned = scanRecordedFields();

        assertThat(scanned)
                .as("감사 적재 필드가 바뀌었다. 새 필드가 민감정보면 Masker.SENSITIVE_FIELDS에, 아니면 "
                        + "AuditFieldMaskingPolicyTest.RECORDED_AUDIT_FIELDS에 추가하고 D-XX에 근거를 남길 것. 실측=%s", scanned)
                .containsExactlyInAnyOrderElementsOf(RECORDED_AUDIT_FIELDS);
    }

    @Test
    @DisplayName("적재 필드 중 마스킹되는 것은 정확히 박제된 민감 필드뿐이다(정책과 적재가 어긋나면 깨진다)")
    void maskedFields_matchPinnedSensitiveSet() {
        Map<String, Object> after = RECORDED_AUDIT_FIELDS.stream()
                .collect(Collectors.toMap(field -> field, field -> "값-" + field));

        Map<String, Object> masked = masker.mask(diffBuilder.diff(Map.of(), after));

        Set<String> actuallyMasked = masked.entrySet().stream()
                .filter(entry -> Masker.MASK.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(actuallyMasked)
                .as("가려지는 필드가 늘거나 줄었다. 새 민감 필드를 적재하기 시작했거나(가려지는 게 늘었다) "
                        + "가려야 할 필드를 정책에서 빠뜨렸다(줄었다)")
                .containsExactlyInAnyOrderElementsOf(MASKED_AUDIT_FIELDS);
        assertThat(masked).hasSameSizeAs(RECORDED_AUDIT_FIELDS);
    }

    @Test
    @DisplayName("마스킹 자체는 살아 있다 — 민감 필드명이 실리면 값이 가려진다(positive control)")
    void sensitiveField_isMasked() {
        Map<String, Object> masked = masker.mask(
                diffBuilder.diff(Map.of(), Map.of("temporaryPassword", "PLAINTEXT-1234")));

        assertThat(masked).containsOnlyKeys("temporaryPassword");
        assertThat(masked.get("temporaryPassword")).isEqualTo(Masker.MASK);
    }

    // ---------- 소스 스캔 ----------

    /** {@code Map.of("k", v, ...)} / {@code map.put("k", v)}의 키 리터럴. */
    private static final Pattern MAP_OF = Pattern.compile("Map\\.of\\(([^;]*?)\\)\\s*[,;)]", Pattern.DOTALL);
    private static final Pattern PUT_KEY = Pattern.compile("\\.put\\(\"(\\w+)\"");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\w+)\"");

    /**
     * {@code auditRecorder.record(...)} 호출이 있는 main 소스에서 diff 맵 키를 모은다.
     *
     * <p>두 가지 조립 방식을 함께 훑는다: (1) 호출 인자에 바로 쓴 {@code Map.of(...)} (2) 호출 앞에서 만든
     * {@code LinkedHashMap}에 {@code put("키", …)}으로 채운 뒤 넘기는 방식(조건부 필드가 있는 거부·검수 경로).
     * 후자는 호출식 안에 키가 없으므로 같은 파일의 {@code put} 키를 함께 센다 — 감사 맵 조립 외에 {@code put}을 쓰는
     * 파일이 섞이면 과검출이 되지만, 과검출은 "박제와 다르다"로 드러나 조용히 새는 쪽보다 안전하다.
     */
    private Set<String> scanRecordedFields() throws IOException {
        Set<String> fields = new TreeSet<>();
        for (Path file : auditRecordingSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher mapOf = MAP_OF.matcher(source);
            while (mapOf.find()) {
                String args = mapOf.group(1);
                Matcher literal = STRING_LITERAL.matcher(args);
                int index = 0;
                while (literal.find()) {
                    if (index % 2 == 0) {   // Map.of(k1, v1, k2, v2 …) — 짝수 위치만 키
                        fields.add(literal.group(1));
                    }
                    index++;
                }
            }
            Matcher put = PUT_KEY.matcher(source);
            while (put.find()) {
                fields.add(put.group(1));
            }
        }
        return fields;
    }

    /** main 소스 중 감사 적재를 실제로 호출하는 파일. */
    private List<Path> auditRecordingSources() throws IOException {
        List<Path> found = new ArrayList<>();
        assertThat(Files.isDirectory(MAIN_SOURCE_ROOT))
                .as("소스 루트를 찾지 못했다(작업 디렉터리=%s). Gradle 테스트는 backend/에서 실행된다",
                        Path.of("").toAbsolutePath())
                .isTrue();
        try (Stream<Path> walk = Files.walk(MAIN_SOURCE_ROOT)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                if (Files.readString(path, StandardCharsets.UTF_8).contains("auditRecorder.record(")) {
                    found.add(path);
                }
            }
        }
        assertThat(found).as("감사 적재 호출부를 하나도 찾지 못했다 — 스캔이 깨졌다").isNotEmpty();
        return found;
    }
}
