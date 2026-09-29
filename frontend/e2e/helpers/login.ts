import { expect, test, type Page } from '@playwright/test'

/**
 * E2E 공용 로그인 헬퍼. 로그인 화면·데모 버튼을 거치지 않고 BE 역할 로그인 API를 page.request로 호출한다(D-235 F10).
 * page.request는 브라우저 컨텍스트의 쿠키 저장소를 공유하므로 BE Set-Cookie(HttpOnly 역할 쿠키·XSRF-TOKEN)가 그대로 컨텍스트에 저장된다
 * (로그인 응답 본문에는 token이 없다). 로그인은 CSRF를 검증하므로 GET /api/v1/auth/csrf로 인증 전 토큰을 먼저 받아 헤더로 싣는다(D-235 PR3 K7).
 * 데모 라우트(/_demo·/_admin-demo·/_seller-demo)는 rate limit(60s/30회·FE-43b)이 있어 워커 수에 따라 전량 실행이 429로 깨졌고,
 * BE 로그인 API에는 제한이 없다(recon-report-e2e-debt §3-1). 데모 로그인 자체의 검증은 admin-shell ⑥·seller-shell ⑤·smoke 구매자 데모 케이스가 담당한다.
 * 자격증명은 env(<ROLE>_E2E_EMAIL / <ROLE>_E2E_PASSWORD)로만 받고 미설정 시 해당 케이스를 skip한다.
 */
export type E2eRole = 'BUYER' | 'ADMIN' | 'SELLER'

const CSRF_TOKEN_API_PATH = '/api/v1/auth/csrf'
const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
const XSRF_HEADER_NAME = 'X-XSRF-TOKEN'

interface RoleSession {
  loginApiPath: string
  emailEnv: string
  passwordEnv: string
}

const ROLE_SESSIONS: Record<E2eRole, RoleSession> = {
  BUYER: { loginApiPath: '/api/v1/auth/buyer/login', emailEnv: 'BUYER_E2E_EMAIL', passwordEnv: 'BUYER_E2E_PASSWORD' },
  ADMIN: { loginApiPath: '/api/v1/admin/auth/login', emailEnv: 'ADMIN_E2E_EMAIL', passwordEnv: 'ADMIN_E2E_PASSWORD' },
  SELLER: { loginApiPath: '/api/v1/seller/auth/login', emailEnv: 'SELLER_E2E_EMAIL', passwordEnv: 'SELLER_E2E_PASSWORD' },
}

/** 역할 자격증명 env가 모두 설정돼 있는지. describe 단위 skip 조건에 쓴다. */
export function hasE2eCredentials(role: E2eRole): boolean {
  const session = ROLE_SESSIONS[role]
  return Boolean(process.env[session.emailEnv]) && Boolean(process.env[session.passwordEnv])
}

/**
 * 역할로 로그인해 BE가 발급한 역할 쿠키를 브라우저 컨텍스트에 받는다. 페이지 이동은 하지 않으므로 호출 뒤 대상 경로로 goto한다.
 * 역할 쿠키는 Secure라 http://localhost에서도 Chromium이 보안 문맥으로 취급해 저장·전송한다.
 */
export async function loginAs(page: Page, role: E2eRole): Promise<void> {
  const session = ROLE_SESSIONS[role]
  const email = process.env[session.emailEnv]
  const password = process.env[session.passwordEnv]
  test.skip(!email || !password, `${session.emailEnv} / ${session.passwordEnv} 미설정`)

  const csrfToken = await fetchCsrfToken(page)
  const response = await page.request.post(session.loginApiPath, {
    data: { email, password },
    headers: { [XSRF_HEADER_NAME]: csrfToken },
  })
  expect(response.ok(), `${role} 로그인 API ${response.status()}`).toBe(true)
}

/** 인증 전 CSRF 토큰(D-235 PR3 K7). 발급 응답의 XSRF-TOKEN이 컨텍스트에 저장되므로(이미 있으면 재발급 없음) 컨텍스트 쿠키에서 값을 읽는다. */
async function fetchCsrfToken(page: Page): Promise<string> {
  const response = await page.request.get(CSRF_TOKEN_API_PATH)
  expect(response.ok(), `CSRF 토큰 발급 API ${response.status()}`).toBe(true)
  const token = (await page.context().cookies()).find((cookie) => cookie.name === XSRF_COOKIE_NAME)?.value
  expect(token, `${XSRF_COOKIE_NAME} 쿠키 없음`).toBeTruthy()
  return token ?? ''
}
