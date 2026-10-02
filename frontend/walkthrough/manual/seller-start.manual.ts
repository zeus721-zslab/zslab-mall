import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture } from './manual-capture'

/**
 * 셀러 매뉴얼 캡처 — 시작하기(로그인 · 화면 구성 · 도움말 위치 · 비밀번호 변경). 영역 키는 layers/seller/app/lib/seller-manual/start.ts와 1:1이다.
 * 로그인 화면은 셀러 세션이 있으면 바로 넘어가므로(seller/login.vue:34-36) 로그인하지 않은 컨텍스트에서 찍는다. 비밀번호 변경 버튼은 누르지 않는다.
 */
test('로그인 화면', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/login')
  await expect(page.locator('#seller-email')).toBeVisible()
  await capture.shot('start-login', {
    email: page.locator('#seller-email'),
    password: page.locator('#seller-password'),
    submit: page.locator('button[type="submit"]'),
  }, { focus: page.locator('form').first() })
})

test.describe('로그인 후', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, 'SELLER')
  })

  test('화면 구성(대시보드)', async ({ page }) => {
    const capture = new ManualCapture(page, 'seller')
    await page.goto('/seller')
    await waitScreen(page, 'dashboard-pending')
    await capture.shot('start-layout', {
      sidebar: page.getByTestId('seller-sidebar'),
      inboxBadge: page.getByTestId('seller-menu-badge-inbox'),
      company: page.getByTestId('seller-company'),
      pending: page.getByTestId('dashboard-pending'),
      account: page.getByTestId('seller-account-menu'),
    }, { fullViewport: true })
  })

  test('계정 메뉴와 도움말', async ({ page }) => {
    const capture = new ManualCapture(page, 'seller')
    await page.goto('/seller')
    await waitScreen(page, 'dashboard-pending')
    await page.getByTestId('seller-account-menu').click()
    await expect(page.getByTestId('seller-help-link')).toBeVisible()
    await capture.shot('start-account-menu', {
      membership: page.getByTestId('seller-membership'),
      help: page.getByTestId('seller-help-link'),
      logout: page.getByTestId('seller-logout'),
    })
  })

  test('비밀번호 변경', async ({ page }) => {
    const capture = new ManualCapture(page, 'seller')
    await page.goto('/seller/settings/password')
    await waitScreen(page, 'seller-password-form-card')
    await capture.shot('start-password', {
      notice: page.getByTestId('seller-password-logout-notice'),
      current: page.getByTestId('seller-current-password'),
      next: page.getByTestId('seller-new-password'),
      submit: page.getByTestId('seller-password-submit'),
    })
  })
})
