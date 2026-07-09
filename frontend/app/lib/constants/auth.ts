/**
 * 로그인 요청 role 단일 소스. BE LoginRequest.role은 ActorRole enum(대문자 정확 일치·"BUYER")을 요구하며,
 * buyer 스토어프론트는 BUYER 고정이다(recon-report-76 §1-2·§3-1). 매직 문자열 방지용 상수 1개만 둔다.
 * ※ ActorRole은 인메모리 인증용이라 4층위 ENUM 잠금 대상이 아니다(ActorRole.java Javadoc).
 */
export const BUYER_ROLE = 'BUYER'
