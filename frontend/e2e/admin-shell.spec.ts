import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 셸(FE-22c Vuetify) 스모크. 관리자 자격증명은 env(ADMIN_E2E_EMAIL/ADMIN_E2E_PASSWORD)로만 받고 미설정 시 skip한다.
 * 컨테이너 dev 서버(:3000) 기준. ①·② 는 관리자 계정, ③ 은 데모 buyer(로그인 페이지 버튼) 사용.
 */
const ADMIN_EMAIL = process.env.ADMIN_E2E_EMAIL
const ADMIN_PASSWORD = process.env.ADMIN_E2E_PASSWORD

/** Vuetify 전역 시트(vuetify/styles의 .v-application 규칙 또는 런타임 테마 시트) 개수 — 누수 판정 지표. */
function countVuetifySheets(page: Page): Promise<number> {
  return page.evaluate(() =>
    Array.from(document.styleSheets).filter((sheet) => {
      if (sheet.ownerNode instanceof Element && sheet.ownerNode.id === 'vuetify-theme-stylesheet') return true
      try {
        return Array.from(sheet.cssRules).some((rule) => rule instanceof CSSStyleRule && rule.selectorText.startsWith('.v-application'))
      } catch {
        // cross-origin 시트는 규칙 접근 불가 → Vuetify 시트 아님
        return false
      }
    }).length,
  )
}

async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/admin/login')
  await page.waitForLoadState('networkidle')
  await page.fill('#admin-email', ADMIN_EMAIL ?? '')
  await page.fill('#admin-password', ADMIN_PASSWORD ?? '')
  await page.click('button[type="submit"]')
  await page.waitForURL(/\/admin$/)
}

test.describe('관리자 셸', () => {
  test.skip(!ADMIN_EMAIL || !ADMIN_PASSWORD, 'ADMIN_E2E_EMAIL / ADMIN_E2E_PASSWORD 미설정')

  test('① 관리자 로그인 → /admin 셸(사이드바·상단바·플레이스홀더) 렌더', async ({ page }) => {
    await loginAsAdmin(page)
    await expect(page.getByTestId('admin-sidebar')).toBeVisible()
    await expect(page.getByTestId('admin-topbar')).toBeVisible()
    await expect(page.getByTestId('admin-placeholder')).toContainText('준비 중입니다')
    expect(await countVuetifySheets(page)).toBeGreaterThan(0)
  })

  test('② 관리자 내부 뒤로가기는 새로고침 없음·관리자 밖 뒤로가기는 전체 새로고침(Vuetify 시트 0·폰트·높이 원복)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const baseline = await page.evaluate(() => ({
      font: getComputedStyle(document.documentElement).fontFamily,
      height: document.documentElement.scrollHeight,
    }))
    await loginAsAdmin(page)
    // 히스토리: / → /admin/login → /admin. 사이드바로 /admin/orders 이동 후 뒤로가기(/admin) = 관리자 내부 → 새로고침 없음(window 마커 유지)
    await page.getByTestId('admin-sidebar').getByText('주문 관리').click()
    await page.getByTestId('admin-sidebar').getByText('전체 주문').click()
    await page.waitForURL(/\/admin\/orders$/)
    await page.evaluate(() => { (window as unknown as { __fe22cMarker: number }).__fe22cMarker = 1 })
    await page.goBack()
    await page.waitForURL(/\/admin$/)
    expect(await page.evaluate(() => (window as unknown as { __fe22cMarker?: number }).__fe22cMarker)).toBe(1)
    expect(await countVuetifySheets(page)).toBeGreaterThan(0)
    // 관리자 밖 뒤로가기(/admin → 두 단계 뒤 /): 이탈 가드가 전체 새로고침 → 마커 소실·Vuetify 시트 0·폰트·높이 기준선 일치
    await page.evaluate(() => history.go(-2))
    await page.waitForURL((url) => url.pathname === '/')
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => (window as unknown as { __fe22cMarker?: number }).__fe22cMarker)).toBeUndefined()
    expect(await countVuetifySheets(page)).toBe(0)
    const after = await page.evaluate(() => ({
      font: getComputedStyle(document.documentElement).fontFamily,
      height: document.documentElement.scrollHeight,
    }))
    expect(after.font).toBe(baseline.font)
    expect(after.height).toBe(baseline.height)
  })

  test('③ BUYER 세션으로 /admin 접근 → /admin/login 도착·auth_token 유지·뒤로가기 시 Vuetify 시트 0', async ({ page, context }) => {
    await page.goto('/login')
    await page.waitForLoadState('networkidle')
    await page.getByRole('button', { name: '데모 계정으로 둘러보기' }).click()
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    await page.goto('/admin')
    await page.waitForURL(/\/admin\/login/)
    await page.waitForLoadState('networkidle')
    expect(await page.locator('#admin-email').count()).toBe(1)
    const cookiesAfterRedirect = await context.cookies()
    expect(cookiesAfterRedirect.some((cookie) => cookie.name === 'auth_token')).toBe(true)
    expect(cookiesAfterRedirect.some((cookie) => cookie.name === 'admin_token')).toBe(false)
    // /admin/login(Vuetify 로드됨)에서 뒤로가기로 사용자 페이지 → 이탈 가드 전체 새로고침 → Vuetify 시트 0
    await page.goBack()
    await page.waitForURL((url) => !url.pathname.startsWith('/admin'))
    await page.waitForLoadState('networkidle')
    expect(await countVuetifySheets(page)).toBe(0)
  })

  test('④ 동시 세션: 관리자 로그인 → 사용자 로그인 → 양쪽 유지 → 관리자 로그아웃 후 사용자 세션 유지·사용자 페이지에 admin_token 미노출', async ({ page, context }) => {
    await loginAsAdmin(page)
    // 사용자 로그인(동일 브라우저·전체 로드)
    await page.goto('/login')
    await page.waitForLoadState('networkidle')
    await page.getByRole('button', { name: '데모 계정으로 둘러보기' }).click()
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    const both = await context.cookies()
    expect(both.find((cookie) => cookie.name === 'admin_token')?.path).toBe('/admin')
    expect(both.some((cookie) => cookie.name === 'auth_token')).toBe(true)
    // 사용자 페이지 document.cookie에 admin_token 미노출(path=/admin)
    const userPageCookie = await page.evaluate(() => document.cookie)
    expect(userPageCookie).not.toContain('admin_token')
    expect(userPageCookie).toContain('auth_token')
    // 관리자 세션 유지 확인 → 로그아웃 → admin_token만 제거
    await page.goto('/admin')
    await expect(page.getByTestId('admin-topbar')).toBeVisible()
    await page.getByTestId('admin-account-menu').click()
    await page.getByTestId('admin-logout').click()
    await page.waitForURL(/\/admin\/login/)
    const afterLogout = await context.cookies()
    expect(afterLogout.some((cookie) => cookie.name === 'admin_token')).toBe(false)
    expect(afterLogout.some((cookie) => cookie.name === 'auth_token')).toBe(true)
    // 사용자 세션은 살아 있다(/mypage 진입 가능)
    await page.goto('/mypage')
    await page.waitForURL((url) => url.pathname === '/mypage')
  })

  test('⑤ 첫 렌더 카드 위치 고정: 카드가 보이는 모든 프레임에서 bbox 동일(이동 0px)', async ({ page }) => {
    await loginAsAdmin(page)
    // 페이지 로드 전에 rAF 기록기를 심어 카드가 나타난 뒤 60프레임 동안 위치를 수집한다(FE-22h 첫 페인트 게이트 검증)
    await page.addInitScript(() => {
      const frames: string[] = []
      const tick = () => {
        const card = document.querySelector('[data-testid="admin-placeholder"]')
        if (card) {
          const box = card.getBoundingClientRect()
          frames.push(`${Math.round(box.x)},${Math.round(box.y)}`)
        }
        if (frames.length < 60) requestAnimationFrame(tick)
      }
      requestAnimationFrame(tick)
      ;(window as unknown as { __cardFrames: string[] }).__cardFrames = frames
    })
    await page.goto('/admin/members')
    await expect(page.getByTestId('admin-placeholder')).toBeVisible()
    await page.waitForFunction(() => (window as unknown as { __cardFrames: string[] }).__cardFrames.length >= 60)
    const frames = await page.evaluate(() => (window as unknown as { __cardFrames: string[] }).__cardFrames)
    expect(new Set(frames).size).toBe(1)
  })
})

// ADMIN_E2E_* 자격증명 없이도 돌아야 하므로 위 describe(skip 조건)와 분리한다.
test.describe('관리자 데모 로그인 (FE-23)', () => {
  test('⑥ 관리자 데모 로그인 버튼 → /admin 셸 진입 · 사용자 auth_token 미생성(FE-23)', async ({ page, context }) => {
    await page.goto('/admin/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('admin-demo-login')
    // 서버 env(NUXT_ADMIN_DEMO_*) 미주입 환경은 버튼이 없으므로 명시 skip(실패 아님)
    test.skip((await demoButton.count()) === 0, 'NUXT_ADMIN_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    await demoButton.click()
    await page.waitForURL(/\/admin$/)
    await expect(page.getByTestId('admin-sidebar')).toBeVisible()
    const cookies = await context.cookies()
    expect(cookies.find((cookie) => cookie.name === 'admin_token')?.path).toBe('/admin')
    expect(cookies.some((cookie) => cookie.name === 'auth_token')).toBe(false)
  })
})
