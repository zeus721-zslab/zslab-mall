/**
 * 셀러 로그인 role 단일 소스(Track 90-A). BE LoginRequest.role은 ActorRole enum("SELLER") 정확 일치를 요구하며,
 * JWT claim role도 동일 문자열이라 미들웨어 판정에 같은 상수를 쓴다. 셀러 내 역할(OWNER·MANAGER·STAFF)은 토큰에 없다.
 */
export const SELLER_ROLE = 'SELLER'

/** 셀러 셸 진입·로그인 경로. 미들웨어·로그인 페이지·로그아웃이 공유한다. */
export const SELLER_HOME_PATH = '/seller'
export const SELLER_LOGIN_PATH = '/seller/login'

/**
 * 셀러 비밀번호 변경 경로(D-3 구매자형 강제). 임시 비밀번호 세션은 미들웨어가 이 경로로만 보낸다.
 * 변경 폼은 Track 90-D-2(FE-50)에서 제공한다(BE PATCH /api/v1/users/me/password 재사용).
 */
export const SELLER_PASSWORD_CHANGE_PATH = '/seller/settings/password'

/**
 * 셀러 로그인 페이지 안내 query(구매자 app/lib/constants/auth LOGIN_NOTICE_* 동형). 비밀번호 변경 204 후 BE가 기존 토큰을 무효화하므로
 * 폼이 logout → 이 query로 로그인 페이지에 보내 재로그인을 안내한다(FE-50).
 */
export const SELLER_LOGIN_NOTICE_QUERY = 'notice'
export const SELLER_LOGIN_NOTICE_PASSWORD_CHANGED = 'password-changed'

/**
 * 셀러 비밀번호 변경 강제 상태 쿠키(D-3). 로그인 응답 passwordChangeRequired=true(임시 비밀번호 로그인)면 저장하고 seller 미들웨어가 판정한다.
 * seller_token과 같은 옵션(non-httpOnly·path /seller)이며 로그아웃 시 지운다. 구매자 password_change_required와 독립.
 */
export const SELLER_PASSWORD_CHANGE_REQUIRED_COOKIE = 'seller_password_change_required'

/**
 * 셀러 데모 로그인 서버 라우트(layers/seller/server/routes/_seller-demo). /api/**(backend 프록시)·/seller/**(CSR 페이지) 밖 경로.
 * status(GET) = { enabled } 버튼 표시 여부 · login(POST) = { token, passwordChangeRequired }.
 */
export const SELLER_DEMO_STATUS_PATH = '/_seller-demo/status'
export const SELLER_DEMO_LOGIN_PATH = '/_seller-demo/login'

/** BE 403 ProblemDetail.code — 정지(SUSPENDED) 셀러의 쓰기 요청 거부(D-190). 세션은 유효하므로 로그아웃하지 않고 안내만 한다. */
export const SELLER_SUSPENDED_ERROR_CODE = 'SELLER_SUSPENDED'
