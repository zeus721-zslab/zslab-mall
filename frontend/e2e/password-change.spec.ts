import { test, expect, type Page } from '@playwright/test'

/**
 * 구매자 비밀번호 변경 강제 흐름(Track 84 FE·D-178) E2E. 로그인·비밀번호 변경 API는 page.route로 mock한다(로그인 응답 passwordChangeRequired=true·
 * 변경 204). 토큰은 role BUYER·미만료 exp를 담은 서명 없는 JWT 형태(FE는 payload만 디코드·실인가는 서버 SoT)라 실제 BE를 건드리지 않는다.
 */
function fakeBuyerJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const exp = Math.floor(Date.now() / 1000) + 3600
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: '1', role: 'BUYER', exp })}.e2e-signature`
}

async function mockAuthApi(page: Page): Promise<{ changes: string[] }> {
  const captured = { changes: [] as string[] }
  await page.route((url) => url.pathname.endsWith('/api/v1/auth/login'), (route) =>
    route.fulfill({ json: { token: fakeBuyerJwt(), passwordChangeRequired: true } }))
  await page.route((url) => url.pathname.endsWith('/api/v1/users/me/password'), (route) => {
    captured.changes.push(route.request().postData() ?? '')
    return route.fulfill({ status: 204 })
  })
  // 강제 상태에서 다른 페이지가 잠깐 렌더돼도 실 API가 호출되지 않도록 프로필·장바구니는 빈 응답으로 막는다.
  await page.route((url) => url.pathname.endsWith('/api/v1/users/me'), (route) =>
    route.fulfill({ json: { publicId: 'usr_E2E', email: 'temp@e2e.invalid', name: 'E2E', phone: '010-0000-0000' } }))
  await page.route((url) => url.pathname.endsWith('/api/v1/cart'), (route) => route.fulfill({ json: { items: [] } }))
  return captured
}

async function loginWithTemporaryPassword(page: Page): Promise<void> {
  await page.goto('/login')
  await page.waitForLoadState('networkidle')
  await page.fill('#email', 'temp@e2e.invalid')
  await page.fill('#password', 'temporary-pw-1')
  await page.click('button[type="submit"]')
}

test.describe('구매자 비밀번호 변경 강제(Track 84)', () => {
  test('① 로그인 응답 플래그 true → 변경 페이지 강제 이동(안내 문구)·마이페이지 등 다른 페이지 차단·쿠키 설정', async ({ page, context }) => {
    await mockAuthApi(page)
    await loginWithTemporaryPassword(page)
    await page.waitForURL((url) => url.pathname === '/mypage/password')
    expect(new URL(page.url()).searchParams.get('reason')).toBe('temporary')
    await expect(page.getByTestId('password-temporary-notice')).toContainText('임시 비밀번호로 로그인했습니다')
    const cookies = await context.cookies()
    expect(cookies.find((cookie) => cookie.name === 'password_change_required')?.value).toBe('true')

    await page.goto('/mypage')
    await page.waitForURL((url) => url.pathname === '/mypage/password')
    await page.goto('/products')
    await page.waitForURL((url) => url.pathname === '/mypage/password')
    // 변경 페이지 자체는 머무를 수 있다(리다이렉트 루프 없음)
    await page.goto('/mypage/password')
    await page.waitForLoadState('networkidle')
    expect(new URL(page.url()).pathname).toBe('/mypage/password')
  })

  test('② 변경 성공(204) → 강제 상태·토큰 해제 → 로그인 페이지 안내 문구 → 이후 다른 페이지 이동 자유', async ({ page, context }) => {
    const captured = await mockAuthApi(page)
    await loginWithTemporaryPassword(page)
    await page.waitForURL((url) => url.pathname === '/mypage/password')
    await page.fill('#currentPassword', 'temporary-pw-1')
    await page.fill('#newPassword', 'brand-new-password-9')
    await page.fill('#newPasswordConfirm', 'brand-new-password-9')
    await page.click('button[type="submit"]')
    await page.waitForURL((url) => url.pathname === '/login')
    expect(new URL(page.url()).searchParams.get('notice')).toBe('password-changed')
    await expect(page.getByTestId('login-password-changed-notice')).toContainText('비밀번호가 변경되었습니다. 다시 로그인해 주세요.')
    expect(JSON.parse(captured.changes[0] ?? '{}')).toEqual({ currentPassword: 'temporary-pw-1', newPassword: 'brand-new-password-9' })
    const cookies = await context.cookies()
    expect(cookies.some((cookie) => cookie.name === 'auth_token')).toBe(false)
    expect(cookies.some((cookie) => cookie.name === 'password_change_required')).toBe(false)

    await page.goto('/products')
    await page.waitForLoadState('networkidle')
    expect(new URL(page.url()).pathname).toBe('/products')
  })
})

// 구매자 데모 로그인(FE-43) 자체를 검증하는 유일한 E2E — 다른 spec은 공용 헬퍼 loginAs(helpers/login.ts)로 세션을 심는다(데모 라우트 rate limit 회피).
// 관리자 admin-shell ⑥·셀러 seller-shell ⑤와 동형. 로그인 API mock 없이 실 데모 라우트를 탄다.
test.describe('구매자 데모 로그인 (FE-43)', () => {
  test('③ 데모 버튼 표시 → 클릭 → POST /_demo/login 200 → 홈 진입 · auth_token 생성 · admin_token/seller_token 미생성', async ({ page, context }) => {
    await page.goto('/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('demo-login')
    // 서버 env(NUXT_BUYER_DEMO_*) 미주입 환경은 버튼이 없으므로 명시 skip(실패 아님)
    test.skip((await demoButton.count()) === 0, 'NUXT_BUYER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    await expect(demoButton).toBeVisible()
    const [response] = await Promise.all([
      page.waitForResponse((candidate) => candidate.url().includes('/_demo/login') && candidate.request().method() === 'POST'),
      demoButton.click(),
    ])
    expect(response.status()).toBe(200)
    await page.waitForURL((url) => url.pathname === '/')
    const cookies = await context.cookies()
    expect(cookies.find((cookie) => cookie.name === 'auth_token')?.path).toBe('/')
    expect(cookies.some((cookie) => cookie.name === 'admin_token')).toBe(false)
    expect(cookies.some((cookie) => cookie.name === 'seller_token')).toBe(false)
  })
})
