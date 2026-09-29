import { ADMIN_DEMO_LOGIN_PATH, ADMIN_ROLE } from '#layers/admin/app/lib/constants/auth'
import { applyCsrfHeader } from '~/lib/csrf'

const UNAUTHORIZED_STATUS = 401

/**
 * 관리자 세션 스토어(setup store·FE-22d·D-235 PR2). 인증은 BE 발급 HttpOnly 관리자 쿠키(__Secure-admin_at · Path /api/v1/admin)라 JS가 읽지 않고,
 * 로그인 상태는 GET /api/v1/admin/me 결과로만 판정한다(F5). 로그인·로그아웃·401 처리는 관리자 상태만 다루며 구매자·셀러 상태에 손대지 않는다.
 * @pinia/nuxt는 루트 stores/만 auto-import하므로 소비처가 `#layers/admin/app/stores/adminAuth`로 명시 import한다.
 */
export const useAdminAuthStore = defineStore('adminAuth', () => {
  /** 관리자 로그인 상태. /admin/me 200이면 true, 401이면 false, null은 아직 확인 전(또는 401 외 실패)이다. */
  const signedIn = ref<boolean | null>(null)
  const role = computed<string | null>(() => (signedIn.value ? ADMIN_ROLE : null))
  const isAuthenticated = computed<boolean>(() => signedIn.value === true)

  /**
   * 로그인 상태가 확인 전이면 GET /api/v1/admin/me로 1회 확인한다(admin·vuetify 미들웨어). 401 로그인 이동은 미들웨어가 복귀 경로와 함께 하므로
   * useAdminApi(401 시 즉시 이동)를 쓰지 않는다. 401 외 실패는 확인 전 상태로 두고 다음 호출에서 다시 확인한다.
   */
  async function ensureSession(): Promise<void> {
    if (signedIn.value !== null) return
    try {
      await $fetch('/v1/admin/me', { baseURL: apiBase() })
      signedIn.value = true
    } catch (error) {
      if ((error as { statusCode?: number }).statusCode === UNAUTHORIZED_STATUS) {
        clearSession()
        return
      }
      console.error('[adminAuth] 관리자 로그인 상태 확인 실패(확인 전 상태 유지)', error)
    }
  }

  /**
   * 관리자 로그인. POST /api/v1/admin/auth/login body { email, password } → BE가 관리자 쿠키를 Set-Cookie로 발급한다(본문 token 없음). 로그인도
   * CSRF를 검증하므로 인증 전 토큰을 헤더로 싣는다(PR3 K7). /admin/**는 CSR 전용(D-9)이라 브라우저 baseURL만 쓴다. 실패(RFC7807)는 $fetch가
   * throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string): Promise<void> {
    await $fetch('/v1/admin/auth/login', {
      baseURL: apiBase(),
      method: 'POST',
      body: { email, password },
      async onRequest({ options }) {
        await applyCsrfHeader(options)
      },
    })
    signedIn.value = true
  }

  /**
   * 관리자 데모 로그인(FE-23). Nuxt 서버 라우트가 env 계정으로 BE 관리자 로그인을 대행하고 BE Set-Cookie를 그대로 전달하므로 브라우저는 자격증명을 모른다.
   * 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    await $fetch(ADMIN_DEMO_LOGIN_PATH, {
      method: 'POST',
      async onRequest({ options }) {
        await applyCsrfHeader(options)
      },
    })
    signedIn.value = true
  }

  /** 관리자 로그아웃(F6). POST /api/v1/admin/auth/logout(CSRF 헤더 · 관리자 쿠키만 만료)을 호출한 뒤 상태를 초기화한다. */
  async function logout(): Promise<void> {
    try {
      await $fetch('/v1/admin/auth/logout', {
        baseURL: apiBase(),
        method: 'POST',
        async onRequest({ options }) {
          await applyCsrfHeader(options)
        },
      })
    } catch (error) {
      // 로그아웃 요청 실패가 화면 로그아웃을 막지 않도록 기록만 하고 상태는 초기화한다.
      console.warn('[adminAuth] 로그아웃 요청 실패(로컬 상태만 초기화)', error)
    }
    clearSession()
  }

  /** 관리자 상태만 로그아웃으로 둔다(로그아웃·관리자 API 401 공용). */
  function clearSession(): void {
    signedIn.value = false
  }

  function apiBase(): string {
    return useRuntimeConfig().public.apiBase || '/api'
  }

  return { signedIn, role, isAuthenticated, ensureSession, login, loginDemo, logout, clearSession }
})
