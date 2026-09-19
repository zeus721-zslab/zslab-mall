import {
  SELLER_DEMO_LOGIN_PATH,
  SELLER_HOME_PATH,
  SELLER_PASSWORD_CHANGE_REQUIRED_COOKIE,
  SELLER_ROLE,
} from '#layers/seller/app/lib/constants/auth'
import { decodeJwtPayload } from '#layers/seller/app/lib/jwt'
import type { JwtPayload } from '~/types/auth'

/** 셀러 세션 쿠키명. 사용자 세션(auth_token)·관리자 세션(admin_token)과 독립(FE-22d α 역할별 쿠키 패턴). */
export const SELLER_TOKEN_COOKIE = 'seller_token'
/** 쿠키 수명(초). BE JWT exp(1h)와 정렬(app/stores/auth.ts와 동일 기준). */
const SELLER_TOKEN_MAX_AGE_SECONDS = 3600

/** BE 로그인 응답 계약(/api/v1/auth/login → LoginResponse). 서버 라우트 데모 대행(/_seller-demo/login)도 같은 형태를 돌려준다. */
interface SellerLoginResponse {
  token: string
  passwordChangeRequired?: boolean
}

/**
 * 셀러 세션 스토어(setup store·Track 90-A). 쿠키 path=/seller라 사용자·관리자 페이지 요청·document.cookie에 노출되지 않는다.
 * @pinia/nuxt는 루트 stores/만 auto-import하므로 소비처가 `#layers/seller/app/stores/sellerAuth`로 명시 import한다.
 * 로그인·로그아웃·401 처리는 이 레이어 쿠키만 다루며 auth_token·admin_token에 손대지 않는다.
 * passwordChangeRequired는 D-3 구매자형(강제 변경) — 임시 비밀번호 로그인이면 seller 미들웨어가 비밀번호 변경 경로로만 보낸다.
 */
export const useSellerAuthStore = defineStore('sellerAuth', () => {
  const cookieOptions = {
    path: SELLER_HOME_PATH,
    sameSite: 'lax' as const,
    secure: true,
    maxAge: SELLER_TOKEN_MAX_AGE_SECONDS,
  }
  const token = useCookie<string | null>(SELLER_TOKEN_COOKIE, cookieOptions)
  const passwordChangeRequiredCookie = useCookie<boolean | null>(SELLER_PASSWORD_CHANGE_REQUIRED_COOKIE, cookieOptions)
  const passwordChangeRequired = computed<boolean>(() => passwordChangeRequiredCookie.value === true)

  const payload = computed<JwtPayload | null>(() => (token.value ? decodeJwtPayload(token.value) : null))
  const role = computed<string | null>(() => payload.value?.role ?? null)
  const exp = computed<number | null>(() => payload.value?.exp ?? null)
  const expired = computed<boolean>(() => (exp.value ? exp.value * 1000 < Date.now() : false))
  const isAuthenticated = computed<boolean>(() => Boolean(token.value) && !expired.value)

  /**
   * 정지(SUSPENDED) 셀러 안내 상태(D-190). 셀러 API 403 SELLER_SUSPENDED를 받으면 켜진다. 세션은 유효(조회 가능)하므로 쿠키가 아닌
   * 메모리 상태이며 로그아웃·새 로그인 시 지워진다. 셀러 `me` 조회 API가 아직 없어 진입 시점엔 알 수 없고 첫 쓰기 거부에서 드러난다.
   */
  const suspended = ref<boolean>(false)

  /**
   * 셀러 로그인. POST /api/v1/auth/login body { email, password, role: SELLER } → { token, passwordChangeRequired }.
   * /seller/**는 CSR 전용이라 브라우저 baseURL만 쓴다. 실패(RFC7807·상태 차단 포함 401 통합)는 $fetch가 throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string): Promise<void> {
    const config = useRuntimeConfig()
    const response = await $fetch<SellerLoginResponse>('/v1/auth/login', {
      baseURL: config.public.apiBase || '/api',
      method: 'POST',
      body: { email, password, role: SELLER_ROLE },
    })
    storeLoginResponse(response)
  }

  /**
   * 셀러 데모 로그인. Nuxt 서버 라우트가 env 계정으로 BE 로그인(SELLER)을 대행하므로 브라우저는 자격증명을 모른다.
   * 응답 형태·role 검증·저장은 login과 동일 경로(storeLoginResponse)를 탄다. 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    const response = await $fetch<SellerLoginResponse>(SELLER_DEMO_LOGIN_PATH, { method: 'POST' })
    storeLoginResponse(response)
  }

  /**
   * 로그인 응답 반영(login·loginDemo 공용). 토큰 role 클레임이 SELLER가 아니면 저장하지 않고 throw한다(BE는 요청 role로 발급하므로 방어 검증).
   * 임시 비밀번호 로그인이면 변경 강제 상태를 켠다(D-3). 필드가 없는 응답은 false로 본다.
   */
  function storeLoginResponse(response: SellerLoginResponse): void {
    if (decodeJwtPayload(response.token)?.role !== SELLER_ROLE) {
      throw new Error('셀러 토큰이 아닙니다')
    }
    token.value = response.token
    passwordChangeRequiredCookie.value = response.passwordChangeRequired === true ? true : null
    suspended.value = false
  }

  /** 403 SELLER_SUSPENDED 수신 시 호출(useSellerApi). 세션은 유지한다. */
  function markSuspended(): void {
    suspended.value = true
  }

  /** 셀러 로그아웃. seller_token·변경 강제 상태만 제거(auth_token·admin_token 유지). */
  function logout(): void {
    token.value = null
    passwordChangeRequiredCookie.value = null
    suspended.value = false
  }

  return { token, role, exp, expired, isAuthenticated, passwordChangeRequired, suspended, login, loginDemo, markSuspended, logout }
})
