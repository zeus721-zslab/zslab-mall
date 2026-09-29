import { BUYER_ROLE, DEMO_LOGIN_PATH, PASSWORD_CHANGE_REQUIRED_COOKIE } from '~/lib/constants/auth'
import { applyCsrfHeader } from '~/lib/csrf'

/**
 * BE 구매자 로그인 응답 계약(/api/v1/auth/buyer/login → LoginResponse). 서버 라우트 데모 대행(/_demo/login)도 같은 형태를 돌려준다.
 * 인증은 응답 Set-Cookie(HttpOnly 구매자 쿠키)로 받으므로 본문 token은 읽지 않는다(D-235 F1).
 */
interface LoginResponse {
  passwordChangeRequired?: boolean
}

const UNAUTHORIZED_STATUS = 401

/** 로그인 상태·인증 동작(setup store). @pinia/nuxt가 stores/를 auto-import한다. */
export const useAuthStore = defineStore('auth', () => {
  // 비밀번호 변경 강제 상태(Track 84·D-178·임시 비밀번호 로그인). 토큰이 아니라 로그인 응답 값이며 전역 미들웨어가 읽는다.
  const passwordChangeRequiredCookie = useCookie<boolean | null>(PASSWORD_CHANGE_REQUIRED_COOKIE, {
    path: '/',
    sameSite: 'lax',
    secure: true,
    maxAge: 3600,
  })
  const passwordChangeRequired = computed<boolean>(() => passwordChangeRequiredCookie.value === true)

  /**
   * 구매자 로그인 상태(D-235 F5). GET /api/v1/users/me 200이면 true, 401이면 false, null은 아직 확인 전(또는 401 외 실패)이다.
   * 구매자 쿠키는 HttpOnly라 JS가 읽을 수 없으므로 서버 조회 결과만 쓴다. SSR에서 확인한 값이 클라이언트로 하이드레이션된다.
   */
  const signedIn = ref<boolean | null>(null)
  const role = computed<string | null>(() => (signedIn.value ? BUYER_ROLE : null))
  const isAuthenticated = computed<boolean>(() => signedIn.value === true)

  /**
   * 로그인 상태가 확인 전이면 /users/me로 1회 확인한다(plugins/auth-session). 401 외 실패는 확인 전 상태로 두어, SSR에서 실패했으면
   * 클라이언트 진입 시 플러그인이 한 번 더 확인한다. 그 뒤에도 실패하면 새로고침 전까지 비로그인으로 보인다.
   */
  async function ensureSession(): Promise<void> {
    if (signedIn.value !== null) return
    try {
      await useBuyerApi()('/v1/users/me')
      signedIn.value = true
    } catch (error) {
      if ((error as { statusCode?: number }).statusCode === UNAUTHORIZED_STATUS) {
        clearSession()
        return
      }
      console.error('[auth] 구매자 로그인 상태 확인 실패(확인 전 상태 유지)', error)
    }
  }

  /**
   * 로그인. POST /api/v1/auth/buyer/login body { email, password } → BE가 구매자 쿠키를 Set-Cookie로 발급한다.
   * 실패(RFC7807)는 $fetch가 throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string): Promise<void> {
    const response = await useBuyerApi()<LoginResponse>('/v1/auth/buyer/login', {
      method: 'POST',
      body: { email, password },
    })
    storeLoginResponse(response)
  }

  /**
   * 구매자 데모 로그인(FE-43). Nuxt 서버 라우트가 비공개 env 계정으로 BE 구매자 로그인을 대행하고 BE Set-Cookie를 그대로 전달하므로 브라우저는 자격증명을 모른다.
   * 응답 반영은 login과 동일 경로(storeLoginResponse)를 탄다. 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    const response = await $fetch<LoginResponse>(DEMO_LOGIN_PATH, {
      method: 'POST',
      async onRequest({ options }) {
        await applyCsrfHeader(options)
      },
    })
    storeLoginResponse(response)
  }

  /** 로그인 응답 반영(login·loginDemo 공용). 로그인 상태를 켜고 임시 비밀번호 로그인이면 변경 강제 상태를 켠다(Track 84·D-178). 필드가 없는 응답은 false로 본다. */
  function storeLoginResponse(response: LoginResponse): void {
    signedIn.value = true
    passwordChangeRequiredCookie.value = response.passwordChangeRequired === true ? true : null
  }

  /**
   * 회원가입. POST /api/v1/users body { email, name, phone, password }.
   * SignupResponse{userPublicId}만 반환하고 쿠키는 미발급이므로, 성공 후 login을 재호출해 자동 로그인한다.
   * 가입 자체 실패(RFC7807·이메일 중복 409 등)는 $fetch가 throw하므로 호출부가 처리한다.
   * 가입은 됐으나 이어진 login이 실패하면 login이 throw하며, 이땐 호출부가 로그인 페이지로 유도한다.
   */
  async function signup(email: string, name: string, phone: string, password: string): Promise<void> {
    // 가입 실패(RFC7807·이메일 중복 409 등)는 그대로 throw → 호출부가 사유 매핑.
    await useBuyerApi()('/v1/users', {
      method: 'POST',
      body: { email, name, phone, password },
    })
    // 쿠키 미발급 응답이므로 자동 로그인을 위해 재로그인.
    try {
      await login(email, password)
    } catch (loginError) {
      // 가입은 성공·자동 로그인만 실패 → 플래그로 구분해 호출부가 /login으로 유도하게 한다.
      const wrapped = new Error('회원가입 후 자동 로그인 실패', { cause: loginError })
      ;(wrapped as { signupSucceeded?: boolean }).signupSucceeded = true
      throw wrapped
    }
  }

  /**
   * 로그아웃(D-235 F6). POST /api/v1/auth/logout(CSRF 헤더 · 구매자 쿠키 만료)을 호출한 뒤 상태를 초기화한다.
   * cart 초기화는 호출부가 조합한다(여기서 store 결합 금지).
   */
  async function logout(): Promise<void> {
    try {
      await useBuyerApi()('/v1/auth/logout', { method: 'POST' })
    } catch (error) {
      // 로그아웃 요청 실패가 화면 로그아웃을 막지 않도록 기록만 하고 상태는 초기화한다.
      console.warn('[auth] 로그아웃 요청 실패(로컬 상태만 초기화)', error)
    }
    clearSession()
  }

  /** 구매자 상태만 로그아웃으로 둔다(로그아웃·구매자 API 401 공용 · 다른 역할 상태는 건드리지 않는다). */
  function clearSession(): void {
    signedIn.value = false
    passwordChangeRequiredCookie.value = null
  }

  /** 본인 비밀번호 변경 완료 시 강제 상태 해제(Track 84). 쿠키는 BE가 무효화하므로 호출부가 이어서 logout한다. */
  function clearPasswordChangeRequired(): void {
    passwordChangeRequiredCookie.value = null
  }

  return {
    signedIn,
    role,
    isAuthenticated,
    passwordChangeRequired,
    ensureSession,
    login,
    loginDemo,
    signup,
    logout,
    clearSession,
    clearPasswordChangeRequired,
  }
})
