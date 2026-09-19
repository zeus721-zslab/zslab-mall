/**
 * 로그인 요청 role 단일 소스. BE LoginRequest.role은 ActorRole enum(대문자 정확 일치·"BUYER")을 요구하며,
 * buyer 스토어프론트는 BUYER 고정이다(recon-report-76 §1-2·§3-1). 매직 문자열 방지용 상수 1개만 둔다.
 * ※ ActorRole은 인메모리 인증용이라 4층위 ENUM 잠금 대상이 아니다(ActorRole.java Javadoc).
 */
export const BUYER_ROLE = 'BUYER'

/**
 * 비밀번호 변경 강제 상태 쿠키(Track 84·D-178). 로그인 응답 passwordChangeRequired=true(임시 비밀번호 로그인)면 '1'을 저장하고
 * 전역 미들웨어가 비밀번호 변경 페이지로 보낸다. auth_token과 같은 옵션(non-httpOnly·path /)이며 로그아웃·변경 완료 시 지운다.
 */
export const PASSWORD_CHANGE_REQUIRED_COOKIE = 'password_change_required'
/** 강제 이동 대상(비밀번호 변경 페이지)·이동 사유 query(안내 문구 표시). */
export const PASSWORD_CHANGE_PATH = '/mypage/password'
export const PASSWORD_CHANGE_REASON_QUERY = 'reason'
export const PASSWORD_CHANGE_REASON_TEMPORARY = 'temporary'
/** 변경 완료 후 로그인 페이지 안내 query. */
export const LOGIN_NOTICE_QUERY = 'notice'
export const LOGIN_NOTICE_PASSWORD_CHANGED = 'password-changed'

/**
 * 구매자 데모 로그인 서버 라우트(FE-43·server/routes/_demo). /api/**(backend 프록시) 밖 경로.
 * status(GET) = { enabled } 버튼 표시 여부 · login(POST) = { token, passwordChangeRequired }. 자격증명은 서버 비공개 runtimeConfig에만 있다.
 */
export const DEMO_STATUS_PATH = '/_demo/status'
export const DEMO_LOGIN_PATH = '/_demo/login'
