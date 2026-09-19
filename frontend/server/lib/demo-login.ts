/**
 * 데모 로그인 코어(FE-23 관리자·FE-43 구매자 공용·Nitro 무의존). 라우트 핸들러가 얇은 어댑터로 감싸며, h3·nitropack을 import하지 않아
 * vitest(nuxt 환경·h3 비호이스팅)에서 직접 검증한다. 자격증명은 비공개 runtimeConfig에서만 읽고 어떤 반환값·예외에도 싣지 않는다.
 */

/** 라우트가 비공개 runtimeConfig에서 꺼내 넘기는 자격증명. 두 값 모두 non-blank여야 데모가 활성이다. */
export interface DemoCredentials {
  email: string
  password: string
}

/** BE LoginRequest.role(ActorRole enum·대문자 정확 일치). 어느 계정으로 대행할지는 라우트가 정한다. */
export type DemoRole = 'BUYER' | 'ADMIN' | 'SELLER'

/** BE 로그인 응답 계약(/api/v1/auth/login → LoginResponse{ token, passwordChangeRequired }). 클라이언트 스토어 login과 동일 형태. */
export interface DemoLoginResponse {
  token: string
  passwordChangeRequired: boolean
}

/** 관리자 데모 라우트(/_admin-demo/login) 응답 계약. { token }만 — BE 응답에 필드가 늘어도 실수로 투과되지 않도록 반환 타입으로 고정한다. */
export interface AdminDemoLoginResponse {
  token: string
}

export const HTTP_NOT_FOUND = 404
export const HTTP_UNAUTHORIZED = 401

export type DemoLoginResult =
  | { ok: true; body: DemoLoginResponse }
  | { ok: false; statusCode: typeof HTTP_NOT_FOUND | typeof HTTP_UNAUTHORIZED }

/** BE 로그인 호출 시그니처(라우트는 fetch, 테스트는 mock 주입). */
export type BackendLoginFetcher = (url: string, body: { email: string; password: string; role: DemoRole }) => Promise<DemoLoginResponse>

const BACKEND_LOGIN_PATH = '/api/v1/auth/login'

export function isDemoConfigured(credentials: DemoCredentials): boolean {
  return credentials.email.trim() !== '' && credentials.password.trim() !== ''
}

/**
 * env 계정으로 BE 로그인을 대행한다. 미설정 404(라우트 부재와 동일 취급)·BE 실패(401·네트워크 등 전부) 401 일반 응답.
 * BE 오류 본문·메시지는 자격증명 힌트가 될 수 있어 전달하지 않는다.
 */
export async function loginAsDemo(credentials: DemoCredentials, role: DemoRole, apiInternalBase: string, fetcher: BackendLoginFetcher): Promise<DemoLoginResult> {
  if (!isDemoConfigured(credentials)) {
    return { ok: false, statusCode: HTTP_NOT_FOUND }
  }
  try {
    const response = await fetcher(`${apiInternalBase}${BACKEND_LOGIN_PATH}`, {
      email: credentials.email,
      password: credentials.password,
      role,
    })
    return { ok: true, body: { token: response.token, passwordChangeRequired: response.passwordChangeRequired === true } }
  } catch (error) {
    // 사유(비번 불일치·계정 탈퇴·BE 다운)는 서버 로그로만 남기고 클라이언트엔 401 단일 응답(자격증명 은닉).
    console.warn(`[demo-login:${role}] backend login failed`, error instanceof Error ? error.message : String(error))
    return { ok: false, statusCode: HTTP_UNAUTHORIZED }
  }
}

/**
 * BE 로그인 호출(라우트 공용). 전역 $fetch는 nitro 타입드 라우트 추론이 임의 문자열 URL에서 TS2321(Excessive stack depth)을 내므로
 * 서버 내부 절대 URL 호출엔 Node 내장 fetch를 쓴다. 비-2xx는 throw해 코어가 401로 통합한다.
 */
export const fetchBackendLogin: BackendLoginFetcher = async (url, body) => {
  const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  if (!response.ok) {
    throw new Error(`backend login responded ${response.status}`)
  }
  return (await response.json()) as DemoLoginResponse
}
