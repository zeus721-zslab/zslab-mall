import { fetchBackendLogin, HTTP_NOT_FOUND, loginAsDemo, XSRF_COOKIE_NAME, XSRF_HEADER_NAME } from '~~/server/lib/demo-login'
import { consume } from '~~/server/lib/demo-rate-limit'

const HTTP_TOO_MANY_REQUESTS = 429
const HTTP_NO_CONTENT = 204
/** rate limit 키 접두사(라우트별 독립 카운터·_demo와 분리). */
const RATE_LIMIT_ROUTE_KEY = 'admin-demo-login'

/**
 * 관리자 데모 로그인 대행(FE-23). 비공개 runtimeConfig(NUXT_ADMIN_DEMO_EMAIL/PASSWORD)로 BE 관리자 로그인을
 * 서버에서 수행하고 BE Set-Cookie(관리자 쿠키·XSRF-TOKEN)를 브라우저 응답으로 그대로 전달한다(D-235 F9). 본문은 없다(204 · token 미전달).
 * 미설정 404 · BE 실패 401(일반 문구). 로그인 CSRF는 브라우저의 XSRF-TOKEN 쿠키·헤더를 BE로 전달해 BE가 검증한다(D-235 PR3 K7). 실제 관리자 계정 그대로(FE-23)이되, 데모 표식(publicDemo)을 실어 계정·권한 변경과 시더만 BE가 막는다(최종 점검 K1 · 업무 처리는 허용).
 * 인증 없이 실제 관리자 쿠키를 발급하는 경로라 rate limit(60초 30회·현 구성에서 키가 gateway 컨테이너 IP라 라우트별 전역 버킷·FE-43b) 초과 시 429 + Retry-After(본문에 사유·자격증명 힌트 없음).
 */
export default defineEventHandler(async (event): Promise<void> => {
  // 단일 gateway_nginx 경유 전제 — 소켓 remoteAddress(= gateway가 맺은 연결의 IP)를 쓴다. X-Forwarded-For는 클라이언트가 위조할 수 있고
  // nginx $proxy_add_x_forwarded_for가 위조값 뒤에 실 IP를 append하므로 첫 값을 읽는 xForwardedFor 옵션은 매 요청 새 버킷을 만들어 무력화됐다(FE-43 운영 실측).
  // 게이트웨이 다단 구성(앞단 LB 등)으로 바뀌면 신뢰 프록시 홉 수 기반 해소로 재검토. 미확보 시 'unknown' 단일 버킷.
  const clientIp = getRequestIP(event) ?? 'unknown'
  const decision = consume(`${RATE_LIMIT_ROUTE_KEY}:${clientIp}`, Date.now())
  if (!decision.allowed) {
    setResponseHeader(event, 'Retry-After', decision.retryAfterSec)
    throw createError({ statusCode: HTTP_TOO_MANY_REQUESTS, statusMessage: 'Too Many Requests' })
  }

  const config = useRuntimeConfig(event)
  const credentials = { email: config.adminDemoEmail, password: config.adminDemoPassword }
  const csrf = { cookieToken: getCookie(event, XSRF_COOKIE_NAME) ?? null, headerToken: getRequestHeader(event, XSRF_HEADER_NAME) ?? null }
  const result = await loginAsDemo(credentials, 'ADMIN', config.apiInternalBase, csrf, fetchBackendLogin)
  if (!result.ok) {
    throw createError({ statusCode: result.statusCode, statusMessage: result.statusCode === HTTP_NOT_FOUND ? 'Not Found' : 'Unauthorized' })
  }
  // passwordChangeRequired는 관리자 데모 흐름이 소비하지 않아 본문을 싣지 않는다.
  for (const setCookie of result.setCookies) appendResponseHeader(event, 'set-cookie', setCookie)
  setResponseStatus(event, HTTP_NO_CONTENT)
})
