import { HTTP_NOT_FOUND, loginAsAdminDemo, type AdminDemoLoginResponse, type BackendLoginFetcher } from '../../lib/admin-demo-login'

/**
 * BE 로그인 호출. 전역 $fetch는 nitro 타입드 라우트 추론이 임의 문자열 URL에서 TS2321(Excessive stack depth)을 내므로
 * 서버 내부 절대 URL 호출엔 Node 내장 fetch를 쓴다. 비-2xx는 throw해 코어가 401로 통합한다.
 */
const fetchBackendLogin: BackendLoginFetcher = async (url, body) => {
  const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  if (!response.ok) {
    throw new Error(`backend login responded ${response.status}`)
  }
  return (await response.json()) as AdminDemoLoginResponse
}

/**
 * 관리자 데모 로그인 대행(FE-23). 비공개 runtimeConfig(NUXT_ADMIN_DEMO_EMAIL/PASSWORD)로 BE 로그인(role ADMIN)을
 * 서버에서 수행하고 기존 로그인 응답 형태({ token })만 반환한다. 미설정 404 · BE 실패 401(일반 문구).
 * 권한 제한 없음(실제 관리자 계정 그대로)은 zslab 결정(포트폴리오 목적·decisions-fe.md FE-23).
 */
export default defineEventHandler(async (event) => {
  const config = useRuntimeConfig(event)
  const result = await loginAsAdminDemo(config, config.apiInternalBase, fetchBackendLogin)
  if (!result.ok) {
    throw createError({ statusCode: result.statusCode, statusMessage: result.statusCode === HTTP_NOT_FOUND ? 'Not Found' : 'Unauthorized' })
  }
  return result.body
})
