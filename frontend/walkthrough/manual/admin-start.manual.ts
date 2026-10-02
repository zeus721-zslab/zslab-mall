import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 시작하기(로그인 · 화면 구성 · 도움말 위치). 영역 키는 layers/admin/app/lib/admin-manual/start.ts와 1:1이다.
 * 로그인 화면은 관리자 세션이 있으면 바로 다른 화면으로 넘어가므로(admin/login.vue:26-28) 로그인하지 않은 컨텍스트에서 찍는다.
 */
test('로그인 화면', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/login')
  await expect(page.locator('#admin-email')).toBeVisible()
  await capture.shot('start-login', {
    email: page.locator('#admin-email'),
    password: page.locator('#admin-password'),
    submit: page.locator('button[type="submit"]'),
  }, { focus: page.locator('form').first() })
})

test.describe('로그인 후', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, 'ADMIN')
  })

  test('화면 구성(대시보드)', async ({ page }) => {
    const capture = new ManualCapture(page, 'admin')
    await page.goto('/admin')
    await waitScreen(page, 'dashboard-pending')
    await capture.shot('start-layout', {
      sidebar: page.getByTestId('admin-sidebar'),
      inboxBadge: page.getByTestId('admin-menu-badge-inbox'),
      breadcrumb: page.locator('.admin-breadcrumbs'),
      pending: page.getByTestId('dashboard-pending'),
      account: page.getByTestId('admin-account-menu'),
    }, { fullViewport: true })
  })

  test('계정 메뉴와 도움말', async ({ page }) => {
    const capture = new ManualCapture(page, 'admin')
    await page.goto('/admin')
    await waitScreen(page, 'dashboard-pending')
    await page.getByTestId('admin-account-menu').click()
    await expect(page.getByTestId('admin-help-link')).toBeVisible()
    await capture.shot('start-account-menu', {
      account: page.getByTestId('admin-account-menu'),
      help: page.getByTestId('admin-help-link'),
      logout: page.getByTestId('admin-logout'),
    })
  })
})
