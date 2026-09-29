package com.zslab.mall.llm.adapter;

import java.util.List;

/**
 * 리뷰 요약 입력(Track 106-1). 공개 리뷰 기준 집계와 최근 본문만 담는다(작성자 식별 정보 없음).
 *
 * @param averageRating    평균 별점(소수 첫째 자리)
 * @param reviewCount      공개 리뷰 수(1 이상)
 * @param topKeywordLabels 많이 고른 키워드 표시 문구(많은 순)
 * @param recentContents   최근 본문(최신순·실제 모델 어댑터 입력용)
 */
public record ReviewSummaryPrompt(double averageRating, long reviewCount, List<String> topKeywordLabels, List<String> recentContents) {
}
