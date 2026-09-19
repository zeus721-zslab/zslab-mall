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
  // gateway_nginx 경유라 X-Forwarded-For가 실 클라이언트 IP. 미확보(직접 접근·헤더 부재)면 'unknown' 단일 버킷.
  const clientIp = getRequestIP(event, { xForwardedFor: true }) ?? 'unknown'
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
