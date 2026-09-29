import {
  SELLER_DEMO_LOGIN_PATH,
  SELLER_HOME_PATH,
  SELLER_PASSWORD_CHANGE_REQUIRED_COOKIE,
  SELLER_ROLE,
} from '#layers/seller/app/lib/constants/auth'
import { applyCsrfHeader } from '~/lib/csrf'

/** 변경 강제 상태 쿠키 수명(초). BE JWT exp(1h)와 정렬(app/stores/auth.ts와 동일 기준). */
const SELLER_PASSWORD_CHANGE_REQUIRED_MAX_AGE_SECONDS = 3600
const UNAUTHORIZED_STATUS = 401

/**
 * BE 셀러 로그인 응답 계약(/api/v1/seller/auth/login → LoginResponse). 서버 라우트 데모 대행(/_seller-demo/login)도 같은 형태를 돌려준다.
 * 인증은 응답 Set-Cookie(HttpOnly 셀러 쿠키)로 받으므로 본문 token은 읽지 않는다(D-235 F1).
 */
interface SellerLoginResponse {
  passwordChangeRequired?: boolean
}

/**
 * 셀러 세션 스토어(setup store·Track 90-A·D-235 PR2). 인증은 BE 발급 HttpOnly 셀러 쿠키(__Secure-seller_at · Path /api/v1/seller)라 JS가 읽지 않고,
 * 로그인 상태는 GET /api/v1/seller/me 결과로만 판정한다(F5). 로그인·로그아웃·401 처리는 셀러 상태만 다루며 구매자·관리자 상태에 손대지 않는다.
 * @pinia/nuxt는 루트 stores/만 auto-import하므로 소비처가 `#layers/seller/app/stores/sellerAuth`로 명시 import한다.
 * passwordChangeRequired는 D-3 구매자형(강제 변경) — 임시 비밀번호 로그인이면 seller 미들웨어가 비밀번호 변경 경로로만 보낸다.
 */
export const useSellerAuthStore = defineStore('sellerAuth', () => {
  const passwordChangeRequiredCookie = useCookie<boolean | null>(SELLER_PASSWORD_CHANGE_REQUIRED_COOKIE, {
    path: SELLER_HOME_PATH,
    sameSite: 'lax',
    secure: true,
    maxAge: SELLER_PASSWORD_CHANGE_REQUIRED_MAX_AGE_SECONDS,
  })
  const passwordChangeRequired = computed<boolean>(() => passwordChangeRequiredCookie.value === true)

  /** 셀러 로그인 상태. /seller/me 200이면 true, 401(PENDING·TERMINATED·소속 없음 포함)이면 false, null은 아직 확인 전(또는 401 외 실패)이다. */
  const signedIn = ref<boolean | null>(null)
  const role = computed<string | null>(() => (signedIn.value ? SELLER_ROLE : null))
  const isAuthenticated = computed<boolean>(() => signedIn.value === true)

  /**
   * 정지(SUSPENDED) 셀러 안내 상태(D-190). 셀러 API 403 SELLER_SUSPENDED를 받으면 켜진다. 세션은 유효(조회 가능)하므로 쿠키가 아닌
   * 메모리 상태이며 로그아웃·새 로그인 시 지워진다. 진입 시점에는 레이아웃의 /seller/me(useSellerMe)가 켠다.
   */
  const suspended = ref<boolean>(false)

  /**
   * 로그인 상태가 확인 전이면 GET /api/v1/seller/me로 1회 확인한다(seller·seller-vuetify 미들웨어). 401 로그인 이동은 미들웨어가 복귀 경로와 함께 하므로
   * useSellerApi(401 시 즉시 이동)를 쓰지 않는다. 401 외 실패는 확인 전 상태로 두고 다음 호출에서 다시 확인한다.
   */
  async function ensureSession(): Promise<void> {
    if (signedIn.value !== null) return
    try {
      await $fetch('/v1/seller/me', { baseURL: apiBase() })
      signedIn.value = true
    } catch (error) {
      if ((error as { statusCode?: number }).statusCode === UNAUTHORIZED_STATUS) {
        clearSession()
        return
      }
      console.error('[sellerAuth] 셀러 로그인 상태 확인 실패(확인 전 상태 유지)', error)
    }
  }

  /**
   * 셀러 로그인. POST /api/v1/seller/auth/login body { email, password } → BE가 셀러 쿠키를 Set-Cookie로 발급한다.
   * /seller/**는 CSR 전용이라 브라우저 baseURL만 쓴다. 실패(RFC7807·상태 차단 포함 401 통합)는 $fetch가 throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string): Promise<void> {
    const response = await $fetch<SellerLoginResponse>('/v1/seller/auth/login', {
      baseURL: apiBase(),
      method: 'POST',
      body: { email, password },
    })
    storeLoginResponse(response)
  }

  /**
   * 셀러 데모 로그인. Nuxt 서버 라우트가 env 계정으로 BE 셀러 로그인을 대행하고 BE Set-Cookie를 그대로 전달하므로 브라우저는 자격증명을 모른다.
   * 응답 반영은 login과 동일 경로(storeLoginResponse)를 탄다. 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    const response = await $fetch<SellerLoginResponse>(SELLER_DEMO_LOGIN_PATH, { method: 'POST' })
    storeLoginResponse(response)
  }

  /** 로그인 응답 반영(login·loginDemo 공용). 임시 비밀번호 로그인이면 변경 강제 상태를 켠다(D-3). 필드가 없는 응답은 false로 본다. */
  function storeLoginResponse(response: SellerLoginResponse): void {
    signedIn.value = true
    passwordChangeRequiredCookie.value = response.passwordChangeRequired === true ? true : null
    suspended.value = false
  }

  /** 403 SELLER_SUSPENDED 수신 시 호출(useSellerApi). 세션은 유지한다. */
  function markSuspended(): void {
    suspended.value = true
  }

  /** 셀러 로그아웃(F6). POST /api/v1/seller/auth/logout(CSRF 헤더 · 셀러 쿠키만 만료)을 호출한 뒤 상태를 초기화한다. */
  async function logout(): Promise<void> {
    try {
      await $fetch('/v1/seller/auth/logout', {
        baseURL: apiBase(),
        method: 'POST',
        onRequest({ options }) {
          applyCsrfHeader(options)
        },
      })
    } catch (error) {
      // 로그아웃 요청 실패가 화면 로그아웃을 막지 않도록 기록만 하고 상태는 초기화한다.
      console.warn('[sellerAuth] 로그아웃 요청 실패(로컬 상태만 초기화)', error)
    }
    clearSession()
  }

  /** 셀러 상태·변경 강제 상태만 로그아웃으로 둔다(로그아웃·셀러 API 401 공용). */
  function clearSession(): void {
    signedIn.value = false
    passwordChangeRequiredCookie.value = null
    suspended.value = false
  }

  function apiBase(): string {
    return useRuntimeConfig().public.apiBase || '/api'
  }

  return {
    signedIn,
    role,
    isAuthenticated,
    passwordChangeRequired,
    suspended,
    ensureSession,
    login,
    loginDemo,
    markSuspended,
    logout,
    clearSession,
  }
})
