import { fetchBackendLogin, HTTP_NOT_FOUND, loginAsDemo, type AdminDemoLoginResponse } from '~~/server/lib/demo-login'
import { consume } from '~~/server/lib/demo-rate-limit'

const HTTP_TOO_MANY_REQUESTS = 429
/** rate limit 키 접두사(라우트별 독립 카운터·_demo와 분리). */
const RATE_LIMIT_ROUTE_KEY = 'admin-demo-login'

/**
 * 관리자 데모 로그인 대행(FE-23). 비공개 runtimeConfig(NUXT_ADMIN_DEMO_EMAIL/PASSWORD)로 BE 로그인(role ADMIN)을
 * 서버에서 수행하고 기존 로그인 응답 형태({ token })만 반환한다. 미설정 404 · BE 실패 401(일반 문구).
 * 권한 제한 없음(실제 관리자 계정 그대로)은 zslab 결정(포트폴리오 목적·decisions-fe.md FE-23).
 * 인증 없이 실제 ADMIN JWT를 발급하는 경로라 IP별 rate limit(60초 10회) 초과 시 429 + Retry-After(본문에 사유·자격증명 힌트 없음).
 */
export default defineEventHandler(async (event): Promise<AdminDemoLoginResponse> => {
  // gateway_nginx 경유라 X-Forwarded-For가 실 클라이언트 IP. 미확보(직접 접근·헤더 부재)면 'unknown' 단일 버킷.
  const clientIp = getRequestIP(event, { xForwardedFor: true }) ?? 'unknown'
  const decision = consume(`${RATE_LIMIT_ROUTE_KEY}:${clientIp}`, Date.now())
  if (!decision.allowed) {
    setResponseHeader(event, 'Retry-After', decision.retryAfterSec)
    throw createError({ statusCode: HTTP_TOO_MANY_REQUESTS, statusMessage: 'Too Many Requests' })
  }

  const config = useRuntimeConfig(event)
  const credentials = { email: config.adminDemoEmail, password: config.adminDemoPassword }
  const result = await loginAsDemo(credentials, 'ADMIN', config.apiInternalBase, fetchBackendLogin)
  if (!result.ok) {
    throw createError({ statusCode: result.statusCode, statusMessage: result.statusCode === HTTP_NOT_FOUND ? 'Not Found' : 'Unauthorized' })
  }
  // 응답 형태 불변({ token }만). passwordChangeRequired는 관리자 데모 흐름이 소비하지 않는다.
  return { token: result.body.token }
})
