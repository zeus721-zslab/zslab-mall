package com.zslab.mall.demoseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.demoseed.service.DemoQuestionQuota;
import com.zslab.mall.demoseed.service.DemoQuestionTimeline;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates.QuestionAnswer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 데모 시더 순수 로직(D-244): 부족분 계산 · 시각 구간 · 문구 제약(서비스 직접 호출로 빠지는 DTO 검증을 문구 목록에서 고정). */
class DemoSeedPlanningTest {

    private static final Pattern FORBIDDEN = Pattern.compile("https?://|www\\.|@|\\d{2,3}-\\d{3,4}-\\d{4}");
    private static final List<String> CATEGORIES = List.of("리빙·주방", "의류", "잡화", "디지털", "문구", "데모");

    @Test
    @DisplayName("부족분: 빈 상품 = 10(미답변 = 결정적 목표 1~5) · 10 이상 미답변 0 = +1 · 기존 미답변 초과는 유지 · 충족 = 0")
    void quota() {
        int target = DemoQuestionQuota.unansweredTarget(42L);
        assertThat(target).isBetween(1, 5).isEqualTo(DemoQuestionQuota.unansweredTarget(42L));
        assertThat(DemoQuestionQuota.of(42L, 0, 0)).isEqualTo(new DemoQuestionQuota(target, 10 - target));
        assertThat(DemoQuestionQuota.of(42L, 12, 0)).isEqualTo(new DemoQuestionQuota(1, 0));
        assertThat(DemoQuestionQuota.of(42L, 7, 7)).isEqualTo(new DemoQuestionQuota(0, 3));
        assertThat(DemoQuestionQuota.of(42L, 11, 3).total()).isZero();
        // 재호출 멱등: 첫 호출 결과 상태(공개 10 · 미답변 target)에서 추가 0
        assertThat(DemoQuestionQuota.of(42L, 10, target).total()).isZero();
    }

    @Test
    @DisplayName("시각: 질문은 하한 이후·현재 이전 · 답변은 질문 이후·현재 이전(하한이 현재에 가까워도)")
    void timeline() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0);
        Random random = new Random(7);
        for (LocalDateTime lower : List.of(now.minusDays(200), now.minusDays(3), now.minusMinutes(30))) {
            for (int index = 0; index < 50; index++) {
                LocalDateTime asked = DemoQuestionTimeline.questionAt(lower, now, random);
                LocalDateTime answered = DemoQuestionTimeline.answerAt(asked, now, random);
                assertThat(asked).isAfterOrEqualTo(lower).isBefore(now);
                assertThat(answered).isAfter(asked).isBeforeOrEqualTo(now);
            }
        }
    }

    @Test
    @DisplayName("문구: 카테고리마다 10~15쌍 · 질문 5~500자·답변 1~1000자 · 카테고리 안 질문 중복 없음 · 링크·연락처·이메일 없음")
    void templates() {
        assertThat(DemoProductQuestionTemplates.all()).containsOnlyKeys(CATEGORIES);
        for (String category : CATEGORIES) {
            List<QuestionAnswer> pairs = DemoProductQuestionTemplates.forCategory(category);
            assertThat(pairs).as(category).hasSizeBetween(10, 15);
            assertThat(pairs.stream().map(QuestionAnswer::question).toList()).as(category).doesNotHaveDuplicates();
            for (QuestionAnswer pair : pairs) {
                assertThat(pair.question().trim().length()).as(pair.question()).isBetween(5, 500);
                assertThat(pair.answer().trim().length()).as(pair.answer()).isBetween(1, 1000);
                assertThat(FORBIDDEN.matcher(pair.question() + " " + pair.answer()).find()).as(pair.question()).isFalse();
            }
        }
        assertThat(DemoProductQuestionTemplates.forCategory("없는 카테고리"))
                .isSameAs(DemoProductQuestionTemplates.forCategory(DemoProductQuestionTemplates.FALLBACK_CATEGORY));
    }
}
