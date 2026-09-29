/**
 * CSRF 헤더 부착(D-235 S5 · Spring csrf.spa()). BE가 발급한 XSRF-TOKEN 쿠키(non-HttpOnly · Path /) 원문 값을 unsafe 요청의
 * X-XSRF-TOKEN 헤더로 싣는다. 구매자·셀러·관리자 래퍼가 모두 이 함수를 onRequest에서 호출한다(F3 단일 소스).
 * 값은 요청마다 document.cookie에서 읽는다 — 로그인 응답이 토큰을 새로 발급할 수 있어 캐시하지 않는다.
 * SSR(document 없음)에서는 부착하지 않는다.
 */
export const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const XSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const SAFE_METHODS: readonly string[] = ['GET', 'HEAD', 'OPTIONS', 'TRACE']

export function applyCsrfHeader(options: { method?: string; headers: Headers }): void {
  if (import.meta.server) return
  const method = (options.method ?? 'GET').toUpperCase()
  if (SAFE_METHODS.includes(method)) return
  const token = readCookie(XSRF_COOKIE_NAME)
  if (token) options.headers.set(XSRF_HEADER_NAME, token)
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  const entry = document.cookie.split('; ').find((part) => part.startsWith(prefix))
  return entry ? entry.slice(prefix.length) : null
}
