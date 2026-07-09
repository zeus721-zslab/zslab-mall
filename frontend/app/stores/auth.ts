import type { JwtPayload } from '~/types/auth'

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

    const response = await $fetch<{ token: string }>('/v1/auth/login', {
      baseURL: baseUrl,
      method: 'POST',
      body: { email, password, role },
    })
    token.value = response.token
  }

  /** 로그아웃. 토큰 쿠키 제거. cart 초기화는 STEP 3 로그아웃 핸들러에서 배선(여기서 store 결합 금지). */
  function logout(): void {
    token.value = null
  }

  return { token, role, exp, expired, isAuthenticated, login, logout }
})
