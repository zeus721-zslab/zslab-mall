package com.zslab.mall.llm.adapter;

import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 규칙·템플릿 요약(Track 106-1·D-209 Mock 어댑터). 외부 호출 없이 평균 별점·리뷰 수·많이 고른 키워드로 한 문장을 만든다. 본문은 쓰지 않는다.
 * {@code zslab.llm.provider=mock}(기본·미설정 포함)일 때만 등록된다.
 */
@Component
@ConditionalOnProperty(name = "zslab.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmAdapter implements LlmPort {

    @Override
    public String summarizeReviews(ReviewSummaryPrompt prompt) {
        String rating = String.format(Locale.ROOT, "%.1f", prompt.averageRating());
        String head = "평균 별점 " + rating + "점(리뷰 " + prompt.reviewCount() + "개)";
        if (prompt.topKeywordLabels().isEmpty()) {
            return head + "이에요.";
        }
        String keywords = prompt.topKeywordLabels().stream().map(label -> "'" + label + "'").collect(Collectors.joining("·"));
        return head + " · " + keywords + " 평가가 많아요.";
    }
}
