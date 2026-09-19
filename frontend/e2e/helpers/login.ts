import { expect, test, type Page } from '@playwright/test'

/**
 * E2E 공용 로그인 헬퍼. 로그인 화면·데모 버튼을 거치지 않고 BE 로그인(POST /api/v1/auth/login)으로 받은 JWT를 역할별 세션 쿠키에 직접 심는다.
 * 데모 라우트(/_demo·/_admin-demo·/_seller-demo)는 rate limit(60s/30회·FE-43b)이 있어 워커 수에 따라 전량 실행이 429로 깨졌고,
 * BE 로그인 API에는 제한이 없다(recon-report-e2e-debt §3-1). 데모 로그인 자체의 검증은 admin-shell ⑥·seller-shell ⑤·smoke 구매자 데모 케이스가 담당한다.
 *
 * 쿠키명·path는 각 스토어와 일치해야 미들웨어가 세션으로 인식한다(app/stores/auth.ts · layers/admin/app/stores/adminAuth.ts · layers/seller/app/stores/sellerAuth.ts).
 * 자격증명은 env(<ROLE>_E2E_EMAIL / <ROLE>_E2E_PASSWORD)로만 받고 미설정 시 해당 케이스를 skip한다.
 */
export type E2eRole = 'BUYER' | 'ADMIN' | 'SELLER'

interface RoleSession {
  cookieName: string
  cookiePath: string
  emailEnv: string
  passwordEnv: string
}

const ROLE_SESSIONS: Record<E2eRole, RoleSession> = {
  BUYER: { cookieName: 'auth_token', cookiePath: '/', emailEnv: 'BUYER_E2E_EMAIL', passwordEnv: 'BUYER_E2E_PASSWORD' },
  ADMIN: { cookieName: 'admin_token', cookiePath: '/admin', emailEnv: 'ADMIN_E2E_EMAIL', passwordEnv: 'ADMIN_E2E_PASSWORD' },
  SELLER: { cookieName: 'seller_token', cookiePath: '/seller', emailEnv: 'SELLER_E2E_EMAIL', passwordEnv: 'SELLER_E2E_PASSWORD' },
}

const LOGIN_API_PATH = '/api/v1/auth/login'
const DEFAULT_BASE_URL = 'http://localhost:3000'

/** 역할 자격증명 env가 모두 설정돼 있는지. describe 단위 skip 조건에 쓴다. */
export function hasE2eCredentials(role: E2eRole): boolean {
  const session = ROLE_SESSIONS[role]
  return Boolean(process.env[session.emailEnv]) && Boolean(process.env[session.passwordEnv])
}

/**
 * 역할로 로그인해 세션 쿠키를 심는다. 페이지 이동은 하지 않으므로 호출 뒤 대상 경로로 goto한다.
 * 쿠키는 스토어와 같은 secure·sameSite=lax로 심는다(Chromium은 http://localhost를 보안 문맥으로 취급해 secure 쿠키를 저장·전송한다).
 */
export async function loginAs(page: Page, role: E2eRole): Promise<void> {
  const session = ROLE_SESSIONS[role]
  const email = process.env[session.emailEnv]
  const password = process.env[session.passwordEnv]
  test.skip(!email || !password, `${session.emailEnv} / ${session.passwordEnv} 미설정`)

  const response = await page.request.post(LOGIN_API_PATH, { data: { email, password, role } })
  expect(response.ok(), `${role} 로그인 API ${response.status()}`).toBe(true)
  const { token } = (await response.json()) as { token: string }

  const baseUrl = test.info().project.use.baseURL ?? DEFAULT_BASE_URL
  await page.context().addCookies([
    { name: session.cookieName, value: token, domain: new URL(baseUrl).hostname, path: session.cookiePath, secure: true, sameSite: 'Lax' },
  ])
}
