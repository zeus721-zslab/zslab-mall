/**
 * 관리자 로그인 role 단일 소스(FE-22). BE LoginRequest.role은 ActorRole enum("ADMIN") 정확 일치를 요구하며,
 * JWT claim role도 동일 문자열이라 미들웨어 판정에 같은 상수를 쓴다. 세분 역할(SUPER_ADMIN·ADMIN_OPERATOR)은 토큰에 없다.
 */
export const ADMIN_ROLE = 'ADMIN'

/** 관리자 셸 진입·로그인 경로. 미들웨어·로그인 페이지·로그아웃이 공유한다. */
export const ADMIN_HOME_PATH = '/admin'
export const ADMIN_LOGIN_PATH = '/admin/login'

/**
 * 관리자 데모 로그인 서버 라우트(FE-23·layers/admin/server/routes/_admin-demo). /api/**(backend 프록시)·/admin/**(CSR 페이지) 밖 경로.
 * status(GET) = { enabled } 버튼 표시 여부 · login(POST) = { token }.
 */
export const ADMIN_DEMO_STATUS_PATH = '/_admin-demo/status'
export const ADMIN_DEMO_LOGIN_PATH = '/_admin-demo/login'
