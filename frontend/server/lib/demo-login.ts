/**
 * 데모 로그인 코어(FE-23 관리자·FE-43 구매자 공용·Nitro 무의존). 라우트 핸들러가 얇은 어댑터로 감싸며, h3·nitropack을 import하지 않아
 * vitest(nuxt 환경·h3 비호이스팅)에서 직접 검증한다. 자격증명은 비공개 runtimeConfig에서만 읽고 어떤 반환값·예외에도 싣지 않는다.
 */

/** 라우트가 비공개 runtimeConfig에서 꺼내 넘기는 자격증명. 두 값 모두 non-blank여야 데모가 활성이다. */
export interface DemoCredentials {
  email: string
  password: string
}

/** 어느 역할 계정으로 대행할지는 라우트가 정한다. 역할마다 BE 로그인 경로가 다르다(D-235 S6). */
export type DemoRole = 'BUYER' | 'ADMIN' | 'SELLER'

/**
 * BE 역할 로그인 결과 중 데모 라우트가 쓰는 부분(D-235 F9). 인증은 BE Set-Cookie(역할 쿠키·XSRF-TOKEN)를 브라우저로 그대로 전달하는 것으로 끝나므로
 * 본문 token은 읽지 않는다.
 */
export interface BackendLoginResult {
  passwordChangeRequired: boolean
  setCookies: string[]
}

/** 구매자·셀러 데모 라우트 응답 본문. token 없이 임시 비밀번호 여부만 — 클라이언트 스토어 login 응답 반영과 동일 형태. */
export interface DemoLoginResponse {
  passwordChangeRequired: boolean
}

export const HTTP_NOT_FOUND = 404
export const HTTP_UNAUTHORIZED = 401

export type DemoLoginResult =
  | { ok: true; body: DemoLoginResponse; setCookies: string[] }
  | { ok: false; statusCode: typeof HTTP_NOT_FOUND | typeof HTTP_UNAUTHORIZED }

/**
 * 브라우저 요청의 CSRF 값(D-235 PR3 K7). BE 로그인이 CSRF를 검증하므로 XSRF-TOKEN 쿠키 값과 X-XSRF-TOKEN 헤더 값을 그대로 전달한다 — 비교·검증은
 * BE 한 곳이 하고, 여기서는 없으면 싣지 않는다(BE 403 → 데모 401). 브라우저 쿠키 중 XSRF-TOKEN 외에는 BE로 보내지 않는다.
 */
export interface CsrfForward {
  cookieToken: string | null
  headerToken: string | null
}

export const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const XSRF_HEADER_NAME = 'x-xsrf-token'

/** BE 로그인 호출 시그니처(라우트는 fetch, 테스트는 mock 주입). */
export type BackendLoginFetcher = (
  url: string,
  body: BackendLoginBody,
  csrf: CsrfForward,
) => Promise<BackendLoginResult>

/** BE 역할 로그인 본문. publicDemo는 관리자 데모 대행만 싣는다(BE가 데모 표식 토큰을 발급해 계정·권한 변경과 시더를 막는다·최종 점검 K1). */
export interface BackendLoginBody {
  email: string
  password: string
  publicDemo?: boolean
}

/** BE 역할 로그인 경로(D-235 S6). 요청 본문에 role을 싣지 않고 경로가 역할을 정한다. */
const BACKEND_LOGIN_PATHS: Record<DemoRole, string> = {
  BUYER: '/api/v1/auth/buyer/login',
  SELLER: '/api/v1/seller/auth/login',
  ADMIN: '/api/v1/admin/auth/login',
}

export function isDemoConfigured(credentials: DemoCredentials): boolean {
  return credentials.email.trim() !== '' && credentials.password.trim() !== ''
}

/**
 * env 계정으로 BE 역할 로그인을 대행한다. 미설정 404(라우트 부재와 동일 취급)·BE 실패(401·네트워크 등 전부) 401 일반 응답.
 * BE 오류 본문·메시지는 자격증명 힌트가 될 수 있어 전달하지 않는다.
 */
export async function loginAsDemo(
  credentials: DemoCredentials,
  role: DemoRole,
  apiInternalBase: string,
  csrf: CsrfForward,
  fetcher: BackendLoginFetcher,
): Promise<DemoLoginResult> {
  if (!isDemoConfigured(credentials)) {
    return { ok: false, statusCode: HTTP_NOT_FOUND }
  }
  // 관리자 데모는 실제 SUPER_ADMIN 계정이라(FE-23) 표식을 실어 세션 권한을 줄인다. 구매자·셀러 데모는 표식이 없다(BE도 관리자 로그인에서만 읽음).
  const body: BackendLoginBody = role === 'ADMIN'
    ? { email: credentials.email, password: credentials.password, publicDemo: true }
    : { email: credentials.email, password: credentials.password }
  try {
    const response = await fetcher(`${apiInternalBase}${BACKEND_LOGIN_PATHS[role]}`, body, csrf)
    return { ok: true, body: { passwordChangeRequired: response.passwordChangeRequired === true }, setCookies: response.setCookies }
  } catch (error) {
    // 사유(비번 불일치·계정 탈퇴·BE 다운)는 서버 로그로만 남기고 클라이언트엔 401 단일 응답(자격증명 은닉).
    console.warn(`[demo-login:${role}] backend login failed`, error instanceof Error ? error.message : String(error))
    return { ok: false, statusCode: HTTP_UNAUTHORIZED }
  }
}

/**
 * BE 로그인 호출(라우트 공용). 전역 $fetch는 nitro 타입드 라우트 추론이 임의 문자열 URL에서 TS2321(Excessive stack depth)을 내므로
 * 서버 내부 절대 URL 호출엔 Node 내장 fetch를 쓴다. 비-2xx는 throw해 코어가 401로 통합한다. Set-Cookie는 여러 줄 원문 그대로 모은다.
 */
export const fetchBackendLogin: BackendLoginFetcher = async (url, body, csrf) => {
  const headers: Record<string, string> = { 'content-type': 'application/json' }
  if (csrf.cookieToken) headers.cookie = `${XSRF_COOKIE_NAME}=${csrf.cookieToken}`
  if (csrf.headerToken) headers[XSRF_HEADER_NAME] = csrf.headerToken
  const response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) })
  if (!response.ok) {
    throw new Error(`backend login responded ${response.status}`)
  }
  const responseBody = (await response.json()) as { passwordChangeRequired?: boolean }
  return { passwordChangeRequired: responseBody.passwordChangeRequired === true, setCookies: response.headers.getSetCookie() }
}
