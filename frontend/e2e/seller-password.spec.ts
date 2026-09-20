import { test, expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * 셀러 비밀번호 변경(Track 90-D-2·FE-50) E2E — 실 BE(PATCH /api/v1/users/me/password·SELLER 토큰). 비밀번호를 실제로 바꾸므로 **전용 계정** env
 * SELLER_PASSWORD_E2E_EMAIL / SELLER_PASSWORD_E2E_PASSWORD로만 실행한다(미설정 skip). 다른 spec이 병렬로 쓰는 SELLER_E2E_*(loginAs)·데모 계정
 * NUXT_SELLER_DEMO_*를 쓰면 변경 직후 그 계정의 모든 토큰이 무효(credentials_changed_at·user 단위)라 다른 워커의 세션이 끊긴다.
 * ①은 말미에 반드시 원복한다(finally에서 "새 비밀번호로 로그인되면 되돌린다" 방식 — 단언 실패·타임아웃으로 중단돼도 원복).
 */
const EMAIL = process.env.SELLER_PASSWORD_E2E_EMAIL
const PASSWORD = process.env.SELLER_PASSWORD_E2E_PASSWORD

const LOGIN_API_PATH = '/api/v1/auth/login'
const PASSWORD_API_PATH = '/api/v1/users/me/password'
const SELLER_TOKEN_COOKIE = 'seller_token'
const DEFAULT_BASE_URL = 'http://localhost:3000'

/** BE 로그인(SELLER). 성공이면 토큰, 실패(401)면 null. */
async function loginToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
  const response = await request.post(LOGIN_API_PATH, { data: { email, password, role: 'SELLER' } })
  if (!response.ok()) return null
  const { token } = (await response.json()) as { token: string }
  return token
}

/** BE 로그인으로 받은 토큰을 seller_token 쿠키(path /seller)에 심는다(helpers/login.ts loginAs와 같은 방식·자격증명만 전용 env). */
async function loginAsPasswordAccount(page: Page, email: string, password: string): Promise<void> {
  const token = await loginToken(page.request, email, password)
  expect(token, '전용 계정 로그인 실패(비밀번호가 원복되지 않은 상태일 수 있음)').not.toBeNull()
  const baseUrl = test.info().project.use.baseURL ?? DEFAULT_BASE_URL
  await page.context().addCookies([
    { name: SELLER_TOKEN_COOKIE, value: token!, domain: new URL(baseUrl).hostname, path: '/seller', secure: true, sameSite: 'Lax' },
  ])
}

/** 새 비밀번호로 로그인되면 원래 비밀번호로 되돌린다(204). 로그인되지 않으면 변경 전이므로 할 일 없음. */
async function restorePasswordIfChanged(request: APIRequestContext, email: string, current: string, original: string): Promise<void> {
  const token = await loginToken(request, email, current)
  if (!token) return
  const response = await request.patch(PASSWORD_API_PATH, {
    headers: { Authorization: `Bearer ${token}` },
    data: { currentPassword: current, newPassword: original },
  })
  expect(response.status(), '비밀번호 원복 실패 — 전용 계정 비밀번호를 수동 복구해야 한다').toBe(204)
}

async function fillForm(page: Page, current: string, next: string, confirm: string): Promise<void> {
  await page.fill('#seller-current-password', current)
  await page.fill('#seller-new-password', next)
  await page.fill('#seller-new-password-confirm', confirm)
  await page.getByTestId('seller-password-submit').click()
}

test.describe('셀러 비밀번호 변경(90-D-2)', () => {
  test.skip(!EMAIL || !PASSWORD, 'SELLER_PASSWORD_E2E_EMAIL / SELLER_PASSWORD_E2E_PASSWORD 미설정')

  test('① 폼 제출 204 → seller_token 제거·/seller/login?notice=password-changed 안내 → 옛 비밀번호 401·새 비밀번호 로그인 200 → 말미 원복', async ({ page, context, request }) => {
    const email = EMAIL!
    const original = PASSWORD!
    const next = `E2e-${Date.now()}-pw`
    try {
      await loginAsPasswordAccount(page, email, original)
      await page.goto('/seller/settings/password')
      await expect(page.getByTestId('seller-password-logout-notice')).toContainText('구매자 로그인이 함께 로그아웃')
      await expect(page.getByTestId('seller-password-temporary-notice')).toHaveCount(0)

      await fillForm(page, original, next, next)
      await page.waitForURL((url) => url.pathname === '/seller/login' && url.searchParams.get('notice') === 'password-changed')
      await expect(page.getByTestId('seller-login-password-changed-notice')).toContainText('비밀번호가 변경되었습니다')
      const cookies = await context.cookies()
      expect(cookies.some((cookie) => cookie.name === SELLER_TOKEN_COOKIE)).toBe(false)

      // BE 반영: 옛 비밀번호는 401, 새 비밀번호는 200
      expect(await loginToken(request, email, original)).toBeNull()
      expect(await loginToken(request, email, next)).not.toBeNull()
    } finally {
      await restorePasswordIfChanged(request, email, next, original)
    }
    // 원복 확인: 원래 비밀번호로 다시 로그인된다
    expect(await loginToken(request, email, original)).not.toBeNull()
  })

  test('② 현재 비밀번호 불일치 → 400 MALFORMED_REQUEST → 현재 비밀번호 필드 오류·URL 유지·세션 유지 · 확인 불일치 → API 미호출 필드 오류', async ({ page, context }) => {
    await loginAsPasswordAccount(page, EMAIL!, PASSWORD!)
    await page.goto('/seller/settings/password')
    await expect(page.getByTestId('seller-password-form')).toBeVisible()

    let passwordCalls = 0
    page.on('request', (request) => {
      if (request.url().includes(PASSWORD_API_PATH) && request.method() === 'PATCH') passwordCalls += 1
    })

    // 확인 불일치: 클라이언트 검증만·API 미호출
    await fillForm(page, 'wrong-current-pw', 'new-password-e2e', 'new-password-other')
    await expect(page.getByTestId('seller-new-password-confirm')).toContainText('새 비밀번호가 일치하지 않습니다')
    expect(passwordCalls).toBe(0)

    // 현재 비밀번호 불일치: 실 BE 400 → 현재 비밀번호 필드
    await fillForm(page, 'wrong-current-pw', 'new-password-e2e', 'new-password-e2e')
    await expect(page.getByTestId('seller-current-password')).toContainText('현재 비밀번호가 일치하지 않습니다')
    await expect.poll(() => passwordCalls).toBe(1)
    expect(new URL(page.url()).pathname).toBe('/seller/settings/password')
    const cookies = await context.cookies()
    expect(cookies.some((cookie) => cookie.name === SELLER_TOKEN_COOKIE)).toBe(true)
  })
})
