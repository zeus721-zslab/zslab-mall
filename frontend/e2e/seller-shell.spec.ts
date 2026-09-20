import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 셀러 셸(Track 90-A·Vuetify 자체 인스턴스) 스모크. 셀러 자격증명은 env(SELLER_E2E_EMAIL/SELLER_E2E_PASSWORD)로만 받고 미설정 시 해당 케이스만 skip한다.
 * 컨테이너 dev 서버(:3000) 기준. ①·②는 자격증명 불필요, ③·④는 셀러 계정(폼 로그인), ⑤는 데모 라우트(env NUXT_SELLER_DEMO_*), ⑥의 구매자 세션은 공용 헬퍼 loginAs(BUYER_E2E_*)로 심는다.
 */
const SELLER_EMAIL = process.env.SELLER_E2E_EMAIL
const SELLER_PASSWORD = process.env.SELLER_E2E_PASSWORD

/** Vuetify 전역 시트(vuetify/styles의 .v-application 규칙 또는 런타임 테마 시트) 개수 — 누수 판정 지표(admin-shell과 동일 기준). */
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

async function loginAsSeller(page: Page): Promise<void> {
  await page.goto('/seller/login')
  await page.waitForLoadState('networkidle')
  await page.fill('#seller-email', SELLER_EMAIL ?? '')
  await page.fill('#seller-password', SELLER_PASSWORD ?? '')
  await page.click('button[type="submit"]')
  await page.waitForURL(/\/seller$/)
}

test.describe('셀러 셸 — 공개 경로', () => {
  test('① /seller/login 렌더(이메일·비밀번호·로그인 버튼·noindex)', async ({ page }) => {
    const response = await page.goto('/seller/login')
    expect(response?.headers()['x-robots-tag']).toBe('noindex, nofollow')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('#seller-email')).toBeVisible()
    await expect(page.locator('#seller-password')).toBeVisible()
    await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeVisible()
    expect(await countVuetifySheets(page)).toBeGreaterThan(0)
  })

  test('② 미인증 /seller 진입 → /seller/login?redirect= 리다이렉트', async ({ page }) => {
    await page.goto('/seller')
    // 미들웨어는 encodeURIComponent('/seller')를 붙이지만 브라우저 URL 표시는 %2F를 /로 정규화한다 → 둘 다 허용
    await page.waitForURL(/\/seller\/login\?redirect=(%2F|\/)seller$/)
    await expect(page.locator('#seller-email')).toBeVisible()
  })
})

test.describe('셀러 셸 — 셀러 계정', () => {
  test.skip(!SELLER_EMAIL || !SELLER_PASSWORD, 'SELLER_E2E_EMAIL / SELLER_E2E_PASSWORD 미설정')

  test('③ 셀러 로그인 → /seller 셸(사이드바·상단바·홈) · seller_token path=/seller · 타 세션 쿠키 미생성 · 로그아웃', async ({ page, context }) => {
    await loginAsSeller(page)
    await expect(page.getByTestId('seller-sidebar')).toBeVisible()
    await expect(page.getByTestId('seller-topbar')).toBeVisible()
    await expect(page.getByTestId('seller-dashboard')).toBeVisible()
    // 메뉴 6그룹: 대시보드·주문·배송·정산 활성 링크(90-B-3), 미구현 항목(클레임·상품·재고·통계 3·설정)은 비활성(라우트 없음)
    const sidebar = page.getByTestId('seller-sidebar')
    for (const label of ['주문', '상품', '통계', '정산', '설정']) {
      await expect(sidebar.getByText(label, { exact: true }).first()).toBeVisible()
    }
    await expect(sidebar.locator('.v-list-item--disabled')).toHaveCount(7)
    const cookies = await context.cookies()
    expect(cookies.find((cookie) => cookie.name === 'seller_token')?.path).toBe('/seller')
    expect(cookies.some((cookie) => cookie.name === 'auth_token')).toBe(false)
    expect(cookies.some((cookie) => cookie.name === 'admin_token')).toBe(false)
    // 상단바 계정 메뉴 → 로그아웃 → /seller/login · seller_token 제거
    await page.getByTestId('seller-account-menu').click()
    await page.getByTestId('seller-logout').click()
    await page.waitForURL(/\/seller\/login/)
    expect((await context.cookies()).some((cookie) => cookie.name === 'seller_token')).toBe(false)
  })

  test('④ 셀러 밖 뒤로가기는 전체 새로고침(Vuetify 시트 0·폰트·높이 원복)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const baseline = await page.evaluate(() => ({
      font: getComputedStyle(document.documentElement).fontFamily,
      height: document.documentElement.scrollHeight,
    }))
    await loginAsSeller(page)
    expect(await countVuetifySheets(page)).toBeGreaterThan(0)
    // 히스토리: / → /seller/login → /seller. 두 단계 뒤(/)로 가면 이탈 가드가 전체 새로고침 → Vuetify 시트 0·기준선 일치
    await page.evaluate(() => history.go(-2))
    await page.waitForURL((url) => url.pathname === '/')
    await page.waitForLoadState('networkidle')
    expect(await countVuetifySheets(page)).toBe(0)
    const after = await page.evaluate(() => ({
      font: getComputedStyle(document.documentElement).fontFamily,
      height: document.documentElement.scrollHeight,
    }))
    expect(after.font).toBe(baseline.font)
    expect(after.height).toBe(baseline.height)
  })
})

test.describe('셀러 데모 로그인', () => {
  test('⑤ 셀러 데모 로그인 버튼 → 200 → /seller 셸 진입 · seller_token path=/seller · auth_token 미생성', async ({ page, context }) => {
    await page.goto('/seller/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('seller-demo-login')
    // 서버 env(NUXT_SELLER_DEMO_*) 미주입 환경은 버튼이 없으므로 명시 skip(실패 아님)
    test.skip((await demoButton.count()) === 0, 'NUXT_SELLER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    const [response] = await Promise.all([
      page.waitForResponse((candidate) => candidate.url().includes('/_seller-demo/login') && candidate.request().method() === 'POST'),
      demoButton.click(),
    ])
    expect(response.status()).toBe(200)
    await page.waitForURL(/\/seller$/)
    await expect(page.getByTestId('seller-sidebar')).toBeVisible()
    await expect(page.getByTestId('seller-dashboard')).toBeVisible()
    const cookies = await context.cookies()
    expect(cookies.find((cookie) => cookie.name === 'seller_token')?.path).toBe('/seller')
    expect(cookies.some((cookie) => cookie.name === 'auth_token')).toBe(false)
  })
})

test.describe('셀러 셸 — 세션 격리', () => {
  test('⑥ BUYER 세션으로 /seller 접근 → /seller/login 도착·auth_token 유지·seller_token 없음·뒤로가기 시 Vuetify 시트 0', async ({ page, context }) => {
    await loginAs(page, 'BUYER')
    // 뒤로가기 검증을 위해 사용자 페이지를 히스토리에 먼저 둔다
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.goto('/seller')
    await page.waitForURL(/\/seller\/login/)
    await page.waitForLoadState('networkidle')
    expect(await page.locator('#seller-email').count()).toBe(1)
    const cookies = await context.cookies()
    expect(cookies.some((cookie) => cookie.name === 'auth_token')).toBe(true)
    expect(cookies.some((cookie) => cookie.name === 'seller_token')).toBe(false)
    // /seller/login(Vuetify 로드됨)에서 뒤로가기로 사용자 페이지 → 이탈 가드 전체 새로고침 → Vuetify 시트 0
    await page.goBack()
    await page.waitForURL((url) => !url.pathname.startsWith('/seller'))
    await page.waitForLoadState('networkidle')
    expect(await countVuetifySheets(page)).toBe(0)
  })
})
