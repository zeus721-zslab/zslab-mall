/**
 * CSRF 헤더 부착(D-235 S5·PR3 K7 · Spring csrf.spa()). BE가 발급한 XSRF-TOKEN 쿠키(non-HttpOnly · Path /) 원문 값을 unsafe 요청의
 * X-XSRF-TOKEN 헤더로 싣는다. 구매자·셀러·관리자 래퍼와 로그인·데모 로그인 호출이 모두 이 함수를 onRequest에서 호출한다(F3 단일 소스).
 * 쿠키가 없으면(첫 방문·브라우저 재시작 뒤 세션 쿠키 만료) GET /api/v1/auth/csrf로 인증 전 토큰을 먼저 받는다 — 로그인도 CSRF를 검증하기 때문이다.
 * 동시 요청은 진행 중인 발급 호출 1개를 공유한다. 값은 요청마다 document.cookie에서 읽는다(응답이 토큰을 새로 발급할 수 있어 캐시하지 않는다).
 * SSR(document 없음)에서는 동작하지 않는다.
 */
export const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const XSRF_HEADER_NAME = 'X-XSRF-TOKEN'
/** 인증 전 CSRF 토큰 발급 경로(API base 기준 · BE AuthController#csrf · 204). */
export const CSRF_TOKEN_PATH = '/v1/auth/csrf'

const SAFE_METHODS: readonly string[] = ['GET', 'HEAD', 'OPTIONS', 'TRACE']

let pendingTokenRequest: Promise<void> | null = null

export async function applyCsrfHeader(options: { method?: string; headers: Headers }): Promise<void> {
  if (import.meta.server) return
  const method = (options.method ?? 'GET').toUpperCase()
  if (SAFE_METHODS.includes(method)) return
  const token = readCookie(XSRF_COOKIE_NAME) ?? (await fetchCsrfToken())
  if (token) options.headers.set(XSRF_HEADER_NAME, token)
}

/** XSRF-TOKEN 쿠키를 발급받은 뒤 값을 읽는다. 발급 실패는 헤더 없이 보내 BE 403으로 드러나게 하고 원인만 기록한다. */
async function fetchCsrfToken(): Promise<string | null> {
  pendingTokenRequest ??= $fetch(CSRF_TOKEN_PATH, { baseURL: useRuntimeConfig().public.apiBase || '/api' })
    .then(() => undefined)
    .catch((error: unknown) => {
      console.warn('[csrf] XSRF-TOKEN 발급 실패', error)
    })
    .finally(() => {
      pendingTokenRequest = null
    })
  await pendingTokenRequest
  const token = readCookie(XSRF_COOKIE_NAME)
  if (!token) console.warn('[csrf] XSRF-TOKEN 발급 후에도 쿠키 없음(쿠키 차단·다른 출처 API base 의심)')
  return token
}

/** 쿠키 값. 없거나 빈 값이면 null(빈 토큰은 CSRF 검증을 통과하지 못하므로 없는 것으로 본다). */
function readCookie(name: string): string | null {
  const prefix = `${name}=`
  const entry = document.cookie.split('; ').find((part) => part.startsWith(prefix))
  const value = entry ? entry.slice(prefix.length) : ''
  return value === '' ? null : value
}
