import { test, expect } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 도움말 자리(Track 102 FE-64) E2E. 매뉴얼 본문은 아직 없고 "자리가 열려 있는지"만 본다 —
 * ① 구매자 /help는 비로그인으로도 열린다(푸터 고객센터 링크 포함) ② 관리자·셀러는 계정 메뉴의 도움말로 들어간다.
 */
test.describe('도움말 진입점', () => {
  test('① 구매자 /help는 비로그인 공개 · 푸터 "고객센터"가 연결된다', async ({ page }) => {
    await page.goto('/help')
    await expect(page.getByTestId('help-placeholder')).toHaveText('준비 중입니다.')

    await page.goto('/')
    await page.getByTestId('footer-help-link').click()
    await page.waitForURL(/\/help$/)
    await expect(page.getByTestId('help-placeholder')).toBeVisible()
  })

  test('② 관리자 계정 메뉴 → 도움말', async ({ page }) => {
    await loginAs(page, 'ADMIN')
    await page.goto('/admin')
    await page.getByTestId('admin-account-menu').click()
    await page.getByTestId('admin-help-link').click()
    await page.waitForURL(/\/admin\/help$/)
    await expect(page.getByTestId('admin-help-placeholder')).toContainText('준비 중입니다.')
  })

  test('③ 셀러 계정 메뉴 → 도움말', async ({ page }) => {
    await loginAs(page, 'SELLER')
    await page.goto('/seller')
    await page.getByTestId('seller-account-menu').click()
    await page.getByTestId('seller-help-link').click()
    await page.waitForURL(/\/seller\/help$/)
    await expect(page.getByTestId('seller-help-placeholder')).toContainText('준비 중입니다.')
  })
})
