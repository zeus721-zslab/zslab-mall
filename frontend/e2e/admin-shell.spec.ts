import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 셸(FE-22c Vuetify) 스모크. 관리자 자격증명은 env(ADMIN_E2E_EMAIL/ADMIN_E2E_PASSWORD)로만 받고 미설정 시 skip한다.
 * 컨테이너 dev 서버(:3000) 기준. ①·② 는 관리자 계정(폼 로그인), ③·④ 의 구매자 세션은 공용 헬퍼 loginAs(BUYER_E2E_*)로 심는다.
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

/** vue-sonner 흔적(FE-25 보강): Toaster DOM 또는 data-sonner 규칙을 가진 스타일 시트 개수 — 관리자 밖에서는 0이어야 한다. */
function countSonnerTraces(page: Page): Promise<number> {
  return page.evaluate(() => {
    const dom = document.querySelectorAll('[data-sonner-toaster]').length
    const sheets = Array.from(document.styleSheets).filter((sheet) => {
      try {
        return Array.from(sheet.cssRules).some((rule) => rule instanceof CSSStyleRule && rule.selectorText.includes('data-sonner'))
      } catch {
        return false
      }
    }).length
    return dom + sheets
  })
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

  test('① 관리자 로그인 → /admin 셸(사이드바·상단바·대시보드) 렌더', async ({ page }) => {
    await loginAsAdmin(page)
    await expect(page.getByTestId('admin-sidebar')).toBeVisible()
    await expect(page.getByTestId('admin-topbar')).toBeVisible()
    // FE-33: /admin은 플레이스홀더가 아니라 대시보드 화면이다(상세 검증은 admin-dashboard.spec)
    await expect(page.getByTestId('admin-dashboard')).toBeVisible()
    expect(await countVuetifySheets(page)).toBeGreaterThan(0)
    // FE-28: 주문 관리 하위 취소/반품/교환 3항목 → "취소·반품·교환" 1항목
    await page.getByTestId('admin-sidebar').getByText('주문 관리').click()
    await expect(page.getByTestId('admin-sidebar').getByRole('link', { name: '취소·반품·교환' })).toHaveCount(1)
    await expect(page.getByTestId('admin-sidebar').getByRole('link', { name: '교환', exact: true })).toHaveCount(0)
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
    expect(await countSonnerTraces(page)).toBeGreaterThan(0) // 관리자 셸 안에서는 Toaster가 존재
    // 관리자 밖 뒤로가기(/admin → 두 단계 뒤 /): 이탈 가드가 전체 새로고침 → 마커 소실·Vuetify 시트 0·sonner 흔적 0·폰트·높이 기준선 일치
    await page.evaluate(() => history.go(-2))
    await page.waitForURL((url) => url.pathname === '/')
    await page.waitForLoadState('networkidle')
    expect(await page.evaluate(() => (window as unknown as { __fe22cMarker?: number }).__fe22cMarker)).toBeUndefined()
    expect(await countVuetifySheets(page)).toBe(0)
    expect(await countSonnerTraces(page)).toBe(0)
    const after = await page.evaluate(() => ({
      font: getComputedStyle(document.documentElement).fontFamily,
      height: document.documentElement.scrollHeight,
    }))
    expect(after.font).toBe(baseline.font)
    expect(after.height).toBe(baseline.height)
  })

  test('③ BUYER 세션으로 /admin 접근 → /admin/login 도착·auth_token 유지·뒤로가기 시 Vuetify 시트 0', async ({ page, context }) => {
    await loginAs(page, 'BUYER')
    // 뒤로가기 검증을 위해 사용자 페이지를 히스토리에 먼저 둔다
    await page.goto('/')
    await page.waitForLoadState('networkidle')
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
    await loginAs(page, 'BUYER')
    await page.goto('/')
    await page.waitForLoadState('networkidle')
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
    // 승인 대기 배너(FE-40)는 의도된 기능이며 이 테스트의 측정 대상이 아니다. 배너 유무가 아니라 레이아웃 이동 없음을 보는 테스트이므로
    // 무관 변수를 통제한다(FE-22h 목적 유지): 로컬 DB에 PENDING 셀러가 있으면 배너가 API 응답 후 필터 카드 위에 삽입돼 bbox가 바뀐다.
    await page.route(
      (url) => url.pathname.endsWith('/api/v1/admin/sellers/page') && url.searchParams.get('status') === 'PENDING',
      (route) => route.fulfill({ json: { items: [], page: 0, size: 1, totalCount: 0, hasNext: false } }),
    )
    // 페이지 로드 전에 rAF 기록기를 심어 카드가 나타난 뒤 60프레임 동안 위치를 수집한다(FE-22h 첫 페인트 게이트 검증)
    await page.addInitScript(() => {
      const frames: string[] = []
      const tick = () => {
        const card = document.querySelector('[data-testid="admin-seller-filters"]')
        if (card) {
          const box = card.getBoundingClientRect()
          frames.push(`${Math.round(box.x)},${Math.round(box.y)}`)
        }
        if (frames.length < 60) requestAnimationFrame(tick)
      }
      requestAnimationFrame(tick)
      ;(window as unknown as { __cardFrames: string[] }).__cardFrames = frames
    })
    // Track 84: /admin/members는 실제 목록 화면이 됐고 FE-40에서 셀러 화면도 구현돼 플레이스홀더가 없다 → 셀러 목록의 필터 카드(목록 로딩과 무관하게
    // 위치가 고정되는 첫 카드)로 측정한다.
    await page.goto('/admin/members/sellers')
    await expect(page.getByTestId('admin-seller-filters')).toBeVisible()
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
