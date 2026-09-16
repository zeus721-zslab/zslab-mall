import { ADMIN_DEMO_LOGIN_PATH, ADMIN_HOME_PATH, ADMIN_ROLE } from '#layers/admin/app/lib/constants/auth'
import { decodeJwtPayload } from '#layers/admin/app/lib/jwt'
import type { JwtPayload } from '~/types/auth'

/** 관리자 세션 쿠키명. 사용자 세션(auth_token)과 독립(FE-22d α 역할별 쿠키). */
export const ADMIN_TOKEN_COOKIE = 'admin_token'
/** 쿠키 수명(초). BE JWT exp(1h)와 정렬(app/stores/auth.ts와 동일 기준). */
const ADMIN_TOKEN_MAX_AGE_SECONDS = 3600

/**
 * 관리자 세션 스토어(setup store·FE-22d). 쿠키 path=/admin이라 사용자 페이지 요청·document.cookie에 노출되지 않는다.
 * @pinia/nuxt는 루트 stores/만 auto-import하므로 소비처가 `#layers/admin/app/stores/adminAuth`로 명시 import한다.
 * 로그인·로그아웃·401 처리는 이 쿠키만 다루며 사용자 auth_token에 손대지 않는다.
 */
export const useAdminAuthStore = defineStore('adminAuth', () => {
  const token = useCookie<string | null>(ADMIN_TOKEN_COOKIE, {
    path: ADMIN_HOME_PATH,
    sameSite: 'lax',
    secure: true,
    maxAge: ADMIN_TOKEN_MAX_AGE_SECONDS,
  })

  const payload = computed<JwtPayload | null>(() => (token.value ? decodeJwtPayload(token.value) : null))
  const role = computed<string | null>(() => payload.value?.role ?? null)
  const exp = computed<number | null>(() => payload.value?.exp ?? null)
  const expired = computed<boolean>(() => (exp.value ? exp.value * 1000 < Date.now() : false))
  const isAuthenticated = computed<boolean>(() => Boolean(token.value) && !expired.value)

  /**
   * 관리자 로그인. POST /api/v1/auth/login body { email, password, role: ADMIN } → { token }.
   * /admin/**는 CSR 전용(D-9)이라 브라우저 baseURL만 쓴다. 실패(RFC7807)는 $fetch가 throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string): Promise<void> {
    const config = useRuntimeConfig()
    const response = await $fetch<{ token: string }>('/v1/auth/login', {
      baseURL: config.public.apiBase || '/api',
      method: 'POST',
      body: { email, password, role: ADMIN_ROLE },
    })
    storeAdminToken(response.token)
  }

  /**
   * 관리자 데모 로그인(FE-23). Nuxt 서버 라우트가 env 계정으로 BE 로그인을 대행하므로 브라우저는 자격증명을 모른다.
   * 응답 형태·role 검증·저장은 login과 동일 경로(storeAdminToken)를 탄다. 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    const response = await $fetch<{ token: string }>(ADMIN_DEMO_LOGIN_PATH, { method: 'POST' })
    storeAdminToken(response.token)
  }

  /** 응답 토큰의 role 클레임이 ADMIN이 아니면 저장하지 않고 throw한다(BE는 요청 role로 발급하므로 방어 검증). */
  function storeAdminToken(candidate: string): void {
    if (decodeJwtPayload(candidate)?.role !== ADMIN_ROLE) {
      throw new Error('관리자 토큰이 아닙니다')
    }
    token.value = candidate
  }

  /** 관리자 로그아웃. admin_token만 제거(사용자 auth_token 유지). */
  function logout(): void {
    token.value = null
  }

  return { token, role, exp, expired, isAuthenticated, login, loginDemo, logout }
})
