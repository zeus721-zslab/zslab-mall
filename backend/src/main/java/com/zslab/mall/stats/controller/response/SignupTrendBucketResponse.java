package com.zslab.mall.stats.controller.response;

/**
 * 가입 추이 구간 1행(Track 88). bucketKey·bucketLabel 규약은 매출 추이와 같다({@code StatsBuckets}). activeCumulative는 기간 시작 시점
 * 활성 누적(가입 누계 − 탈퇴 누계)에 구간별 (가입 − 탈퇴)를 순서대로 더한 값(구간 종료 시점 활성 회원 수·윈도우 함수 없이 서비스 누적).
 */
public record SignupTrendBucketResponse(
        String bucketKey,
        String bucketLabel,
        long newCount,
        long activeCumulative) {
}
