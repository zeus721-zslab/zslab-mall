package com.zslab.mall.grade.service;

/**
 * 등급 재산정 배치 결과 요약(Track 51 Phase 3). buyer별 개별 TX 순회의 총 대상·성공·실패 카운트다.
 *
 * <p>lock 기간 buyer는 {@code GradeService.recalculate} 내부에서 예외 없이 skip되므로 success에 포함된다
 * (skip 세분화는 미도입 — recalculate 시그니처 변경을 피하고 배치는 성공/실패만 집계·과잉설계 회피).
 */
public record GradeRecalculationResult(int total, int success, int failure) {
}
