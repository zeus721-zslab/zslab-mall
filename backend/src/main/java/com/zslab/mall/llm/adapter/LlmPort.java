package com.zslab.mall.llm.adapter;

/**
 * 언어 모델 교체 지점(Track 106-1·D-209 외부 연동 포트 패턴). 현재 구현은 {@link MockLlmAdapter}(규칙·템플릿)뿐이고 실제 모델 어댑터는
 * {@code zslab.llm.provider}에 새 값을 더할 때 붙인다. 호출은 화면 렌더링이 아니라 저장 시점(커밋 후 비동기)에만 한다(D-220 "렌더 시 호출
 * 금지 → 생성·저장"). 입력에는 개인정보(작성자 이름·연락처·주소)를 넣지 않는다.
 *
 * <p>실패는 RuntimeException 전파로 표현하고, 재시도·기본 문구 대체는 호출부 책임이다.
 */
public interface LlmPort {

    /** 상품 리뷰 한 줄 요약. */
    String summarizeReviews(ReviewSummaryPrompt prompt);
}
