import { test, expect } from './fixtures'
import { loginAs } from './helpers/login'

/**
 * 매뉴얼(C8) 접근 가드 E2E. /admin/help는 관리자 세션, /seller/help는 셀러 세션이 있어야만 열린다 — 페이지에 걸린 기존 역할 미들웨어
 * (admin/help.vue · seller/help.vue definePageMeta)가 비로그인·다른 역할 세션을 각 역할 로그인 화면으로 보낸다.
 * 캡처 이미지(public/manual/**)는 정적 파일이라 가드 대상이 아니다(zslab 결정).
 */
test.describe('매뉴얼 접근 가드', () => {
  test('비로그인 → /admin/help는 관리자 로그인 화면으로 간다', async ({ page }) => {
    await page.goto('/admin/help')
    await page.waitForURL(/\/admin\/login\?redirect=/)
    await expect(page.getByTestId('manual-shell')).toHaveCount(0)
  })

  test('비로그인 → /seller/help는 셀러 로그인 화면으로 간다', async ({ page }) => {
    await page.goto('/seller/help')
    await page.waitForURL(/\/seller\/login\?redirect=/)
    await expect(page.getByTestId('manual-shell')).toHaveCount(0)
  })

  test('셀러 세션으로 /admin/help에 들어갈 수 없다', async ({ page }) => {
    await loginAs(page, 'SELLER')
    await page.goto('/admin/help')
    await page.waitForURL(/\/admin\/login\?redirect=/)
    await expect(page.getByTestId('manual-shell')).toHaveCount(0)
  })

  test('관리자 세션으로 /seller/help에 들어갈 수 없다', async ({ page }) => {
    await loginAs(page, 'ADMIN')
    await page.goto('/seller/help')
    await page.waitForURL(/\/seller\/login\?redirect=/)
    await expect(page.getByTestId('manual-shell')).toHaveCount(0)
  })

  test('관리자 세션으로 /admin/help 진입 · 해시로 단계에 바로 들어간다', async ({ page }) => {
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/help#flow-claim--bulk')
    await expect(page.getByTestId('manual-title')).toHaveText('관리자 매뉴얼')
    await expect(page.getByTestId('manual-step-bulk')).toBeInViewport()
  })

  test('셀러 세션으로 /seller/help 진입', async ({ page }) => {
    await loginAs(page, 'SELLER')
    await page.goto('/seller/help')
    await expect(page.getByTestId('manual-title')).toHaveText('셀러 매뉴얼')
    await expect(page.getByTestId('manual-toc')).toBeVisible()
  })
})
