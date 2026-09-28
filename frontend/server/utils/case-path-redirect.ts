/**
 * 대소문자 혼합 경로 정규화(FE-92). vue-router는 대소문자를 구분하지 않지만(sensitive:false) 서버 routeRules(radix3)·쿠키 path·
 * 경로 문자열 분기는 구분한다. 그래서 /Admin/login은 관리자 화면에 매칭되면서 ssr:false·X-Robots-Tag 규칙을 놓쳐 SSR 500이 난다.
 * 진입점에서 첫 세그먼트만 소문자로 301 보내 전부를 소문자 경로 기준으로 맞춘다. 최상위 동적 페이지가 없어 첫 세그먼트는 항상
 * 고정 이름이고, 뒤 세그먼트(주문번호 등 파라미터)와 쿼리는 원문 그대로 둔다.
 * h3·nitropack을 import하지 않는 순수 함수라 vitest에서 직접 검증한다.
 */

const REDIRECT_METHODS = new Set(['GET', 'HEAD'])
const ASCII_UPPERCASE = /[A-Z]/
const ASCII_UPPERCASE_GLOBAL = /[A-Z]/g

/**
 * @param method HTTP 메서드
 * @param path 쿼리를 뺀 경로(예: /Admin/login)
 * @param query '?'를 포함한 쿼리 원문(없으면 빈 문자열)
 * @returns 리다이렉트 위치, 정규화 대상이 아니면 null
 */
export function resolveCasePathRedirect(method: string, path: string, query: string): string | null {
  if (!REDIRECT_METHODS.has(method.toUpperCase())) return null

  const firstSegmentEnd = path.indexOf('/', 1)
  const firstSegment = firstSegmentEnd === -1 ? path.slice(1) : path.slice(1, firstSegmentEnd)
  if (firstSegment === '') return null
  // /\Evil.com → /\evil.com은 브라우저가 //evil.com(외부 호스트)으로 해석하므로 정규화하지 않는다(오픈 리다이렉트 방지).
  if (firstSegment.startsWith('\\')) return null

  const decoded = decodeSegment(firstSegment)
  if (decoded === null || !ASCII_UPPERCASE.test(decoded)) return null

  const rest = firstSegmentEnd === -1 ? '' : path.slice(firstSegmentEnd)
  const lowered = firstSegment.replace(ASCII_UPPERCASE_GLOBAL, (letter) => letter.toLowerCase())
  return `/${lowered}${rest}${query}`
}

function decodeSegment(segment: string): string | null {
  try {
    return decodeURIComponent(segment)
  }
  catch {
    // 잘못된 퍼센트 인코딩은 정규화하지 않고 그대로 통과시킨다 — 판정은 뒤 단계(라우터 404 등)에 맡긴다.
    return null
  }
}
