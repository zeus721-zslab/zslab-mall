package com.zslab.mall.grade.exception;

/**
 * 활성 등급 정책 구간에 매칭되는 정책이 없을 때 발생한다(Track 51·AUTO 산정).
 *
 * <p>시드(V15)는 {@code [0, BIGINT MAX]} 전구간을 커버하므로 매칭 실패는 정책 비활성·시드 손상 등 <b>서버 설정 오류</b>다.
 * 클라이언트가 교정할 수 없는 시스템 상태이므로 전역 예외 핸들러가 HTTP 500으로 응답한다(422 아님).
 * 무분류 500 fallback으로 새지 않도록 전용 code(GRADE_POLICY_UNAVAILABLE)를 부여해 운영 진단성을 확보한다.
 */
public class GradePolicyUnavailableException extends RuntimeException {

    public GradePolicyUnavailableException(String message) {
        super(message);
    }
}
