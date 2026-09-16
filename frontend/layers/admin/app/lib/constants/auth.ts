/**
 * 관리자 로그인 role 단일 소스(FE-22). BE LoginRequest.role은 ActorRole enum("ADMIN") 정확 일치를 요구하며,
 * JWT claim role도 동일 문자열이라 미들웨어 판정에 같은 상수를 쓴다. 세분 역할(SUPER_ADMIN·ADMIN_OPERATOR)은 토큰에 없다.
 */
export const ADMIN_ROLE = 'ADMIN'

/** 관리자 셸 진입·로그인 경로. 미들웨어·로그인 페이지·로그아웃이 공유한다. */
export const ADMIN_HOME_PATH = '/admin'
export const ADMIN_LOGIN_PATH = '/admin/login'
