/**
 * 관리자 데모 로그인 코어(FE-23·Nitro 무의존). 라우트 핸들러가 얇은 어댑터로 감싸며, h3·nitropack을 import하지 않아
 * vitest(nuxt 환경·h3 비호이스팅)에서 직접 검증한다. 자격증명은 비공개 runtimeConfig에서만 읽고 어떤 반환값·예외에도 싣지 않는다.
 */

/** 라우트가 읽는 비공개 runtimeConfig 조각. 두 값 모두 non-blank여야 데모가 활성이다. */
export interface AdminDemoConfig {
  adminDemoEmail: string
  adminDemoPassword: string
}

/** BE 로그인 응답 계약(/api/v1/auth/login → { token }). 클라이언트 스토어 login과 동일 형태. */
export interface AdminDemoLoginResponse {
  token: string
}

export const HTTP_NOT_FOUND = 404
export const HTTP_UNAUTHORIZED = 401

export type AdminDemoLoginResult =
  | { ok: true; body: AdminDemoLoginResponse }
  | { ok: false; statusCode: typeof HTTP_NOT_FOUND | typeof HTTP_UNAUTHORIZED }

/** BE 로그인 호출 시그니처(라우트는 $fetch, 테스트는 mock 주입). */
export type BackendLoginFetcher = (url: string, body: { email: string; password: string; role: string }) => Promise<AdminDemoLoginResponse>

const ADMIN_ROLE = 'ADMIN'
const BACKEND_LOGIN_PATH = '/api/v1/auth/login'

export function isAdminDemoConfigured(config: AdminDemoConfig): boolean {
  return config.adminDemoEmail.trim() !== '' && config.adminDemoPassword.trim() !== ''
}

/**
 * env 계정으로 BE 로그인을 대행한다. 미설정 404(라우트 부재와 동일 취급)·BE 실패(401·네트워크 등 전부) 401 일반 응답.
 * BE 오류 본문·메시지는 자격증명 힌트가 될 수 있어 전달하지 않는다.
 */
export async function loginAsAdminDemo(config: AdminDemoConfig, apiInternalBase: string, fetcher: BackendLoginFetcher): Promise<AdminDemoLoginResult> {
  if (!isAdminDemoConfigured(config)) {
    return { ok: false, statusCode: HTTP_NOT_FOUND }
  }
  try {
    const response = await fetcher(`${apiInternalBase}${BACKEND_LOGIN_PATH}`, {
      email: config.adminDemoEmail,
      password: config.adminDemoPassword,
      role: ADMIN_ROLE,
    })
    return { ok: true, body: { token: response.token } }
  } catch (error) {
    // 사유(비번 불일치·계정 탈퇴·BE 다운)는 서버 로그로만 남기고 클라이언트엔 401 단일 응답(자격증명 은닉).
    console.warn('[admin-demo] backend login failed', error instanceof Error ? error.message : String(error))
    return { ok: false, statusCode: HTTP_UNAUTHORIZED }
  }
}
