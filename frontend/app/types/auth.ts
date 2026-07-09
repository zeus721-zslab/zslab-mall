/**
 * 로그인 응답 JWT의 클라이언트 디코드 페이로드(부분 뷰). UI 표시·만료 UX 전용이며 실인가 판단에 쓰지 않는다.
 * 서버가 보내는 다른 claim은 화면에 불필요해 타이핑하지 않는다(role·exp만 소비).
 */
export interface JwtPayload {
  role?: string
  exp?: number
}
