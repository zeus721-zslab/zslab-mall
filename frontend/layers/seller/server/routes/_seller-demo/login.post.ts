import { fetchBackendLogin, HTTP_NOT_FOUND, loginAsDemo, type DemoLoginResponse } from '~~/server/lib/demo-login'
import { consume } from '~~/server/lib/demo-rate-limit'

const HTTP_TOO_MANY_REQUESTS = 429
/** rate limit 키 접두사(라우트별 독립 카운터·_demo·_admin-demo와 분리). */
const RATE_LIMIT_ROUTE_KEY = 'seller-demo-login'

/**
 * 셀러 데모 로그인 대행(Track 90-A). 비공개 runtimeConfig(NUXT_SELLER_DEMO_EMAIL/PASSWORD)로 BE 로그인(role SELLER)을
 * 서버에서 수행하므로 자격증명이 클라이언트 번들·public runtimeConfig에 실리지 않는다. 응답은 스토어 login과 동일한
 * BE 계약({ token, passwordChangeRequired })이라 임시 비밀번호 강제 변경(D-3 구매자형)도 같은 경로로 처리된다.
 * 미설정 404 · BE 실패(셀러 상태 차단 D-190 포함) 401(일반 문구) — _demo·_admin-demo와 동일 규약.
 * 인증 없이 JWT를 발급하는 경로라 rate limit(60초 30회·현 구성에서 키가 gateway 컨테이너 IP라 라우트별 전역 버킷·FE-43b) 초과 시 429 + Retry-After.
 */
export default defineEventHandler(async (event): Promise<DemoLoginResponse> => {
  // 단일 gateway_nginx 경유 전제 — 소켓 remoteAddress를 쓴다(X-Forwarded-For는 위조 가능·FE-43a). 미확보 시 'unknown' 단일 버킷.
  const clientIp = getRequestIP(event) ?? 'unknown'
  const decision = consume(`${RATE_LIMIT_ROUTE_KEY}:${clientIp}`, Date.now())
  if (!decision.allowed) {
    setResponseHeader(event, 'Retry-After', decision.retryAfterSec)
    throw createError({ statusCode: HTTP_TOO_MANY_REQUESTS, statusMessage: 'Too Many Requests' })
  }

  const config = useRuntimeConfig(event)
  const credentials = { email: config.sellerDemoEmail, password: config.sellerDemoPassword }
  const result = await loginAsDemo(credentials, 'SELLER', config.apiInternalBase, fetchBackendLogin)
  if (!result.ok) {
    throw createError({ statusCode: result.statusCode, statusMessage: result.statusCode === HTTP_NOT_FOUND ? 'Not Found' : 'Unauthorized' })
  }
  return result.body
})
