package com.zslab.mall.demoseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.demoseed.service.DemoReviewPlanner;
import com.zslab.mall.demoseed.service.DemoReviewPlanner.Candidate;
import com.zslab.mall.demoseed.service.DemoReviewPlanner.PlannedReview;
import com.zslab.mall.demoseed.template.DemoReviewTemplates;
import com.zslab.mall.demoseed.template.DemoReviewTemplates.Band;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 데모 리뷰 계획 순수 로직(D-245): 별점 가중 · 키워드 수 · 분산 선택 · 상품 안 중복 금지 · 작성 시각 · 문구 제약. */
class DemoReviewPlanningTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final Pattern FORBIDDEN = Pattern.compile("https?://|www\\.|@|\\d{2,3}-\\d{3,4}-\\d{4}");
    private static final List<String> BASE_KEYWORDS = List.of("DELIVERY_FAST", "PACKAGING_NEAT", "QUALITY_GOOD", "VALUE_FOR_MONEY");

    @Test
    @DisplayName("별점: 품목 id로 결정적 · 1000건 분포가 가중치(5:30·4:30·3:20·2:12·1:8)에 가깝다")
    void rating_followsWeights() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (long id = 1; id <= 1000; id++) {
            counts.merge(DemoReviewPlanner.rating(id), 1, Integer::sum);
        }
        assertThat(DemoReviewPlanner.rating(777L)).isEqualTo(DemoReviewPlanner.rating(777L));
        assertThat(counts.get(5)).isBetween(240, 360);
        assertThat(counts.get(4)).isBetween(240, 360);
        assertThat(counts.get(3)).isBetween(140, 260);
        assertThat(counts.get(2)).isBetween(70, 170);
        assertThat(counts.get(1)).isBetween(40, 120);
    }

    @Test
    @DisplayName("계획: 부족분만 · 구매자 고르게 · 상품 안 본문 중복 없음 · 기존 키워드만(별점별 개수) · 확정 이후·현재 이전")
    void plan_spreadsAndRespectsRules() {
        List<Candidate> candidates = new ArrayList<>();
        long id = 1000;
        for (long buyer = 1; buyer <= 4; buyer++) {
            for (long product = 1; product <= 3; product++) {
                for (int month = 3; month <= 5; month++) {
                    candidates.add(new Candidate(id, "oit_" + id, product, "상품" + product, null, buyer, "b" + buyer + "@demo.test",
                            LocalDateTime.of(2026, month, 10, 12, 0)));
                    id++;
                }
            }
        }
        Map<Long, Set<String>> existing = Map.of(1L, Set.of(DemoReviewTemplates.render(
                DemoReviewTemplates.forCategory(null, Band.HIGH).get(0), "상품1")));
        List<PlannedReview> planned = DemoReviewPlanner.plan(20, candidates, existing, Map.of(), Map.of(), NOW);

        assertThat(planned).hasSize(20);
        Map<Long, Long> byBuyer = planned.stream().collect(Collectors.groupingBy(review -> review.candidate().buyerId(), Collectors.counting()));
        // 덜 쓰인 구매자 우선이되 상품별 별점대 문구가 소진되면 그 품목을 건너뛰므로 정확히 5가 아니라 차이 2 이내로 고르다
        assertThat(byBuyer).hasSize(4);
        assertThat(byBuyer.values()).allMatch(count -> count >= 4 && count <= 6);
        for (long product = 1; product <= 3; product++) {
            long productId = product;
            List<String> contents = planned.stream().filter(review -> review.candidate().productId() == productId)
                    .map(PlannedReview::content).toList();
            assertThat(contents).doesNotHaveDuplicates();
        }
        assertThat(planned.stream().filter(review -> review.candidate().productId() == 1L).map(PlannedReview::content).toList())
                .doesNotContainAnyElementsOf(existing.get(1L));
        for (PlannedReview review : planned) {
            assertThat(review.keywordCodes()).isEmpty();   // 쓸 수 있는 키워드 목록이 비면 0개(새 code를 만들지 않는다)
            assertThat(review.writtenAt()).isAfterOrEqualTo(review.candidate().confirmedAt()).isBefore(NOW);
        }
        assertThat(DemoReviewPlanner.plan(20, candidates, existing, Map.of(), Map.of(), NOW)).isEqualTo(planned);
    }

    @Test
    @DisplayName("키워드: 4~5점 1~3개 · 3점 0~1개 · 1~2점 0개 · 쓸 수 있는 목록 안에서만 · 중복 없음")
    void keywords_matchRating() {
        Map<Long, List<String>> usable = Map.of(9L, BASE_KEYWORDS);
        for (long id = 1; id <= 300; id++) {
            Candidate candidate = new Candidate(id, "oit_" + id, id, "상품", 9L, 1L, "b@demo.test", NOW.minusDays(30));
            PlannedReview review = DemoReviewPlanner.plan(1, List.of(candidate), Map.of(), Map.of(), usable, NOW).get(0);
            List<String> codes = review.keywordCodes();
            assertThat(BASE_KEYWORDS).containsAll(codes);
            assertThat(codes).doesNotHaveDuplicates();
            if (review.rating() >= 4) {
                assertThat(codes).hasSizeBetween(1, 3);
            } else if (review.rating() == 3) {
                assertThat(codes).hasSizeBetween(0, 1);
            } else {
                assertThat(codes).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("분산: 연속 품목 id 1000건의 작성 지연 일수(0~4일)와 4~5점 첫 문장이 고르게 퍼진다(연속 시드 편향 방지)")
    void consecutiveIds_spreadEvenly() {
        LocalDateTime confirmed = NOW.minusDays(60);
        int[] delayDays = new int[6];
        Map<String, Integer> highContents = new HashMap<>();
        int high = 0;
        for (long id = 5000; id < 6000; id++) {
            Candidate candidate = new Candidate(id, "oit_" + id, id, "상품", null, 1L, "b@demo.test", confirmed);
            PlannedReview review = DemoReviewPlanner.plan(1, List.of(candidate), Map.of(), Map.of(), Map.of(), NOW).get(0);
            delayDays[(int) java.time.Duration.between(confirmed, review.writtenAt()).toDays()]++;
            if (review.rating() >= 4) {
                high++;
                highContents.merge(review.content(), 1, Integer::sum);
            }
        }
        for (int day = 0; day < 5; day++) {
            assertThat(delayDays[day]).as("지연 %d일대", day).isGreaterThan(120);
        }
        assertThat(highContents).hasSize(4);
        int highTotal = high;
        assertThat(highContents.values()).allMatch(count -> count > highTotal / 8);
    }

    @Test
    @DisplayName("작성 시각: 방금 확정한 품목도 현재 − 1분을 넘지 않고 확정 이전으로 가지 않는다")
    void writtenAt_capsAtNow() {
        Candidate recent = new Candidate(5L, "oit_5", 1L, "상품", null, 1L, "b@demo.test", NOW.minusHours(2));
        PlannedReview review = DemoReviewPlanner.plan(1, List.of(recent), Map.of(), Map.of(), Map.of(), NOW).get(0);
        assertThat(review.writtenAt()).isAfterOrEqualTo(recent.confirmedAt()).isBeforeOrEqualTo(NOW.minusMinutes(1));
    }

    @Test
    @DisplayName("문구: 6카테고리 × 3별점대 = 60문장 · 상품명 자리 포함 · 본문 1~1000자 · 링크·연락처·이메일 없음 · 대체 세트")
    void templates() {
        int sentences = 0;
        for (Map<Band, List<String>> bands : DemoReviewTemplates.all().values()) {
            assertThat(bands).containsOnlyKeys(Band.values());
            for (List<String> templates : bands.values()) {
                assertThat(templates).doesNotHaveDuplicates();
                for (String template : templates) {
                    sentences++;
                    assertThat(template).contains(DemoReviewTemplates.NAME_TOKEN);
                    assertThat(template.trim().length()).isBetween(1, 1000);
                    assertThat(FORBIDDEN.matcher(template).find()).as(template).isFalse();
                }
            }
        }
        assertThat(DemoReviewTemplates.all()).hasSize(6);
        assertThat(sentences).isEqualTo(60);
        assertThat(DemoReviewTemplates.forCategory("없는 카테고리", Band.LOW))
                .isSameAs(DemoReviewTemplates.forCategory(DemoReviewTemplates.FALLBACK_CATEGORY, Band.LOW));
    }
}
