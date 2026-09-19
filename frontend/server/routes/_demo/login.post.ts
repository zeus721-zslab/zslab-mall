import { fetchBackendLogin, HTTP_NOT_FOUND, loginAsDemo } from '~~/server/lib/demo-login'
import { consume } from '~~/server/lib/demo-rate-limit'

const HTTP_TOO_MANY_REQUESTS = 429
/** rate limit 키 접두사(라우트별 독립 카운터·_admin-demo와 분리). */
const RATE_LIMIT_ROUTE_KEY = 'demo-login'

/**
 * 구매자 데모 로그인 대행(FE-43). 비공개 runtimeConfig(NUXT_BUYER_DEMO_EMAIL/PASSWORD)로 BE 로그인(role BUYER)을
 * 서버에서 수행하므로 자격증명이 클라이언트 번들·public runtimeConfig에 실리지 않는다. 응답은 스토어 login과 동일한
 * BE 계약({ token, passwordChangeRequired })이라 임시 비밀번호 강제 변경(Track 84)도 같은 경로로 처리된다.
 * 미설정 404 · BE 실패 401(일반 문구) — _admin-demo와 동일 규약.
 * 인증 없이 JWT를 발급하는 경로라 IP별 rate limit(60초 10회) 초과 시 429 + Retry-After(본문에 사유·자격증명 힌트 없음).
 */
export default defineEventHandler(async (event) => {
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
  const credentials = { email: config.buyerDemoEmail, password: config.buyerDemoPassword }
  const result = await loginAsDemo(credentials, 'BUYER', config.apiInternalBase, fetchBackendLogin)
  if (!result.ok) {
    throw createError({ statusCode: result.statusCode, statusMessage: result.statusCode === HTTP_NOT_FOUND ? 'Not Found' : 'Unauthorized' })
  }
  return result.body
})
