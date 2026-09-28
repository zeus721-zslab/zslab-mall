package com.zslab.mall.common.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 응답 DTO의 LocalDateTime 직렬화 방식 고정 테스트(D-234).
 *
 * <p><b>왜 필요한가</b>: Jackson 3는 Jackson 2의 {@code com.fasterxml.jackson.databind.annotation.JsonSerialize}를 예외 없이
 * 무시한다. 어노테이션 패키지를 잘못 쓰거나 빠뜨리면 {@code +09:00} 오프셋이 컴파일·기동 오류 없이 사라진다. 이 테스트는
 * 응답 DTO 패키지({@code controller/response})의 모든 LocalDateTime 필드(record 컴포넌트 포함)가 Jackson 3
 * {@code @JsonSerialize(using = KstOffsetSerializer.class)}를 갖는지, 아니면 박제된 예외 목록에 있는지를 검사한다.
 */
class KstOffsetSerializationPolicyTest {

    /** 소스 스캔 루트(Gradle 테스트 작업 디렉터리 = backend/). */
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final String RESPONSE_PACKAGE_DIRECTORY = "controller" + java.io.File.separator + "response";

    /**
     * KST 직렬화기 없이 오프셋 없는 문자열로 나가는 필드(2026-09-28 실측 34개/11 DTO). 근거: main에서 오프셋 없이 직렬화 —
     * 형식 통일은 FE 계약 변경이라 범위 밖(D-234 §8 통합 검토 이월). 새 LocalDateTime 응답 필드는 KstOffsetSerializer를 붙이는
     * 것이 기본이고, 여기에 추가하려면 D-XX에 근거를 남긴다.
     */
    private static final Set<String> OFFSETLESS_FIELDS = Set.of(
            "AdminMemberDetailResponse$Grade.lockedUntil",
            "AdminMemberDetailResponse.createdAt",
            "AdminMemberDetailResponse.withdrawnAt",
            "AdminMemberSummaryResponse.createdAt",
            "AdminMemberSummaryResponse.lastPaidAt",
            "AdminMemberSummaryResponse.withdrawnAt",
            "AdminOperatorSummaryResponse.createdAt",
            "AdminOperatorSummaryResponse.withdrawnAt",
            "AdminOrderDetailResponse$CancelReason.recordedAt",
            "AdminOrderDetailResponse$ClaimRow.pickedUpAt",
            "AdminOrderDetailResponse$ClaimRow.processedAt",
            "AdminOrderDetailResponse$ClaimRow.requestedAt",
            "AdminOrderDetailResponse$DeliveryRow.deliveredAt",
            "AdminOrderDetailResponse$DeliveryRow.shippedAt",
            "AdminOrderDetailResponse$PaymentRow.createdAt",
            "AdminOrderDetailResponse$PaymentRow.paidAt",
            "AdminOrderDetailResponse.orderedAt",
            "AdminOrderDetailResponse.paidAt",
            "AdminOrderSummaryResponse.orderedAt",
            "AdminOrderSummaryResponse.paidAt",
            "AdminReconciliationIssueResponse.detectedAt",
            "AdminReconciliationIssueResponse.resolvedAt",
            "AdminSellerBankAccountResponse.createdAt",
            "AdminSellerBankAccountResponse.updatedAt",
            "AdminSellerBankAccountResponse.verifiedAt",
            "AdminSellerDetailResponse$BankAccount.verifiedAt",
            "AdminSellerDetailResponse$Member.joinedAt",
            "AdminSellerDetailResponse$Member.withdrawnAt",
            "AdminSellerDetailResponse.createdAt",
            "AdminSellerDetailResponse.updatedAt",
            "AdminSellerMemberAddResponse.joinedAt",
            "AdminSellerMemberAddResponse.withdrawnAt",
            "AdminSellerSummaryResponse.createdAt",
            "SellerBankAccountResponse.createdAt");

    @Test
    @DisplayName("응답 DTO의 LocalDateTime 필드는 Jackson 3 @JsonSerialize(KstOffsetSerializer)이거나 박제된 예외다")
    void responseLocalDateTimeFields_useKstOffsetSerializerOrPinnedException() throws Exception {
        Set<String> annotated = new TreeSet<>();
        Set<String> offsetless = new TreeSet<>();
        for (Class<?> type : responseTypes()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != LocalDateTime.class) {
                    continue;
                }
                String key = simpleBinaryName(type) + "." + field.getName();
                JsonSerialize serialize = field.getAnnotation(JsonSerialize.class);
                if (serialize != null && KstOffsetSerializer.class.equals(serialize.using())) {
                    annotated.add(key);
                } else {
                    offsetless.add(key);
                }
            }
        }

        assertThat(annotated).as("KST 직렬화 필드를 하나도 찾지 못했다 — 스캔이 깨졌다").isNotEmpty();
        assertThat(offsetless)
                .as("KST 직렬화기가 없는 LocalDateTime 응답 필드가 박제와 다르다. 새 필드면 "
                        + "tools.jackson.databind.annotation.JsonSerialize(using = KstOffsetSerializer.class)를 붙이고, "
                        + "의도적으로 오프셋 없이 내보내는 것이면 OFFSETLESS_FIELDS에 추가하고 D-XX에 근거를 남길 것. 실측=%s", offsetless)
                .containsExactlyInAnyOrderElementsOf(OFFSETLESS_FIELDS);
    }

    /**
     * jjwt-jackson·logstash-logback-encoder가 Jackson 2 databind를 compile 범위로 끌어와 main에서도 import가 컴파일된다.
     * Jackson 2 어노테이션은 Jackson 3가 무시하고, Jackson 2 ObjectMapper는 spring.jackson 설정 밖이라 둘 다 형식이 조용히 달라진다.
     */
    @Test
    @DisplayName("main 소스에 Jackson 2 databind·core(com.fasterxml.jackson.databind·core) 사용이 없다")
    void mainSources_doNotUseJackson2DatabindOrCore() throws IOException {
        List<Path> offenders = new ArrayList<>();
        for (Path file : javaSources(MAIN_SOURCE_ROOT)) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.contains("com.fasterxml.jackson.databind") || source.contains("com.fasterxml.jackson.core")) {
                offenders.add(file);
            }
        }
        assertThat(offenders).as("Jackson 3 설정을 벗어나는 Jackson 2 databind·core를 쓰는 파일").isEmpty();
    }

    /** 응답 DTO 패키지의 최상위 클래스와 그 중첩 클래스 전부. */
    private List<Class<?>> responseTypes() throws IOException, ClassNotFoundException {
        List<Class<?>> types = new ArrayList<>();
        for (Path file : javaSources(MAIN_SOURCE_ROOT)) {
            if (!file.getParent().toString().endsWith(RESPONSE_PACKAGE_DIRECTORY)) {
                continue;
            }
            String relative = MAIN_SOURCE_ROOT.relativize(file).toString();
            String className = relative.substring(0, relative.length() - ".java".length())
                    .replace(java.io.File.separatorChar, '.');
            collectWithNested(Class.forName(className), types);
        }
        assertThat(types).as("응답 DTO 클래스를 하나도 찾지 못했다 — 스캔이 깨졌다").isNotEmpty();
        return types;
    }

    private void collectWithNested(Class<?> type, List<Class<?>> types) {
        types.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectWithNested(nested, types);
        }
    }

    /** 패키지를 뺀 바이너리 이름(중첩 클래스는 Outer$Inner). */
    private String simpleBinaryName(Class<?> type) {
        return type.getName().substring(type.getPackageName().length() + 1);
    }

    private List<Path> javaSources(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
                .as("소스 루트를 찾지 못했다(작업 디렉터리=%s). Gradle 테스트는 backend/에서 실행된다", Path.of("").toAbsolutePath())
                .isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
