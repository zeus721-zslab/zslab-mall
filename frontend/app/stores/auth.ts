import { BUYER_ROLE, DEMO_LOGIN_PATH, PASSWORD_CHANGE_REQUIRED_COOKIE } from '~/lib/constants/auth'
import type { JwtPayload } from '~/types/auth'

/** BE 로그인 응답 계약(/api/v1/auth/login → LoginResponse). 서버 라우트 데모 대행(/_demo/login)도 같은 형태를 돌려준다. */
interface LoginResponse {
  token: string
  passwordChangeRequired?: boolean
}

/**
 * JWT payload를 라이브러리 없이 base64url 수동 디코드한다(UI 표시·만료 UX 전용).
 * '-'/'_' → '+'/'/' 치환·'=' 패딩·UTF-8 안전 복원(멀티바이트 claim 대비).
 * 형식 오류 토큰은 파싱 실패로 간주하고 null 반환(호출부는 미인증으로 취급). 실인가는 서버 응답이 SoT.
 */
function decodeJwtPayload(token: string): JwtPayload | null {
  const parts = token.split('.')
  const payloadSegment = parts[1]
  if (parts.length !== 3 || !payloadSegment) return null
  try {
    const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    const json = new TextDecoder().decode(bytes)
    return JSON.parse(json) as JwtPayload
  } catch {
    // 손상·비표준 토큰은 디코드 불가 → null(미인증 취급). 표시 전용이라 throw하지 않는다.
    return null
  }
}

/** 로그인 토큰·인증 파생 상태(setup store). @pinia/nuxt가 stores/를 auto-import한다. */
export const useAuthStore = defineStore('auth', () => {
  // non-httpOnly: SSR·클라이언트 양쪽 JS가 토큰을 읽어야 함. maxAge는 토큰 exp(1h)와 정렬.
  const token = useCookie<string | null>('auth_token', {
    path: '/',
    sameSite: 'lax',
    secure: true,
    maxAge: 3600,
  })
  // 비밀번호 변경 강제 상태(Track 84·D-178·임시 비밀번호 로그인). auth_token과 같은 옵션·수명이며 전역 미들웨어가 읽는다.
  const passwordChangeRequiredCookie = useCookie<boolean | null>(PASSWORD_CHANGE_REQUIRED_COOKIE, {
    path: '/',
    sameSite: 'lax',
    secure: true,
    maxAge: 3600,
  })
  const passwordChangeRequired = computed<boolean>(() => passwordChangeRequiredCookie.value === true)

  const payload = computed<JwtPayload | null>(() =>
    token.value ? decodeJwtPayload(token.value) : null,
  )
  const role = computed<string | null>(() => payload.value?.role ?? null)
  const exp = computed<number | null>(() => payload.value?.exp ?? null)
  const expired = computed<boolean>(() =>
    exp.value ? exp.value * 1000 < Date.now() : false,
  )
  const isAuthenticated = computed<boolean>(() => Boolean(token.value) && !expired.value)

  /**
   * 로그인. POST /api/v1/auth/login body { email, password, role } → { token }를 쿠키에 저장.
   * 실패(RFC7807)는 $fetch가 throw하므로 호출부가 처리한다.
   */
  async function login(email: string, password: string, role: string): Promise<void> {
    const config = useRuntimeConfig()
    // API base 이원화(useProducts와 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로.
    const baseUrl = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    const response = await $fetch<LoginResponse>('/v1/auth/login', {
      baseURL: baseUrl,
      method: 'POST',
      body: { email, password, role },
    })
    storeLoginResponse(response)
  }

  /**
   * 구매자 데모 로그인(FE-43). Nuxt 서버 라우트가 비공개 env 계정으로 BE 로그인(BUYER)을 대행하므로 브라우저는 자격증명을 모른다.
   * 응답 형태·쿠키 저장은 login과 동일 경로(storeLoginResponse)를 탄다. 미설정 404·BE 실패 401은 $fetch가 throw한다.
   */
  async function loginDemo(): Promise<void> {
    const response = await $fetch<LoginResponse>(DEMO_LOGIN_PATH, { method: 'POST' })
    storeLoginResponse(response)
  }

  /** 로그인 응답 반영(login·loginDemo 공용). 토큰 저장 + 임시 비밀번호 로그인이면 변경 강제 상태를 켠다(Track 84·D-178). 필드가 없는 응답은 false로 본다. */
  function storeLoginResponse(response: LoginResponse): void {
    token.value = response.token
    passwordChangeRequiredCookie.value = response.passwordChangeRequired === true ? true : null
  }

  /**
   * 회원가입. POST /api/v1/users body { email, name, phone, password }.
   * SignupResponse{userPublicId}만 반환하고 토큰은 미발급이므로, 성공 후 login(BUYER)을 재호출해 자동 로그인한다.
   * 가입 자체 실패(RFC7807·이메일 중복 409 등)는 $fetch가 throw하므로 호출부가 처리한다.
   * 가입은 됐으나 이어진 login이 실패하면 login이 throw하며, 이땐 호출부가 로그인 페이지로 유도한다.
   */
  async function signup(email: string, name: string, phone: string, password: string): Promise<void> {
    const config = useRuntimeConfig()
    // API base 이원화(login과 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로.
    const baseUrl = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    // 가입 실패(RFC7807·이메일 중복 409 등)는 그대로 throw → 호출부가 사유 매핑.
    await $fetch('/v1/users', {
      baseURL: baseUrl,
      method: 'POST',
      body: { email, name, phone, password },
    })
    // 토큰 미발급 응답이므로 자동 로그인을 위해 재로그인(role은 buyer 몰 고정 BUYER).
    try {
      await login(email, password, BUYER_ROLE)
    } catch (loginError) {
      // 가입은 성공·자동 로그인만 실패 → 플래그로 구분해 호출부가 /login으로 유도하게 한다.
      const wrapped = new Error('회원가입 후 자동 로그인 실패', { cause: loginError })
      ;(wrapped as { signupSucceeded?: boolean }).signupSucceeded = true
      throw wrapped
    }
  }

  /** 로그아웃. 토큰 쿠키 제거. cart 초기화는 STEP 3 로그아웃 핸들러에서 배선(여기서 store 결합 금지). */
  function logout(): void {
    token.value = null
    passwordChangeRequiredCookie.value = null
  }

  /** 본인 비밀번호 변경 완료 시 강제 상태 해제(Track 84). 토큰은 BE가 무효화하므로 호출부가 이어서 logout한다. */
  function clearPasswordChangeRequired(): void {
    passwordChangeRequiredCookie.value = null
  }

  return { token, role, exp, expired, isAuthenticated, passwordChangeRequired, login, loginDemo, signup, logout, clearPasswordChangeRequired }
})
