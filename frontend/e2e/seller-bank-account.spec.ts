import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe } from './helpers/seller-mock'

/**
 * 셀러 정산계좌 화면(Track 90-D-3·FE-51) E2E. 로그인은 loginAs(SELLER·실 BE), /seller/me(역할)·계좌 목록은 page.route mock — POST는 실 DB에
 * 계좌가 누적되므로 실행하지 않는다(등록 흐름은 BE IT SellerBankAccountControllerIntegrationTest가 커버). ① GET 실 API 렌더(로그인 셀러의
 * 실제 목록 또는 빈 상태·전체 계좌번호 미노출) + 실 me roleCode 대조 폼 표시 ② STAFF me mock → 폼 없음·조회 전용 안내 + 목록 mock 2건(주 계좌 칩 1).
 */
const ACCOUNTS = [
  { id: 1, bankCode: 'KB', accountNumberSuffix: '6789', accountHolder: 'E2E 대표', isPrimary: true, status: 'VERIFIED', createdAt: '2026-09-21T10:00:00' },
  { id: 2, bankCode: 'SHINHAN', accountNumberSuffix: '4321', accountHolder: 'E2E 대표', isPrimary: false, status: 'VERIFIED', createdAt: '2026-09-21T11:00:00' },
]

async function gotoBankAccount(page: Page): Promise<void> {
  await page.goto('/seller/settings/bank-account')
  await expect(page.getByTestId('seller-bank-account')).toBeVisible()
}

test.describe('셀러 정산계좌 화면(90-D-3)', () => {
  test('① 실 me·실 목록 GET → 사이드바 설정 그룹 활성·목록(행 또는 빈 상태)·전체 계좌번호 미노출·roleCode 대조 폼/안내', async ({ page }) => {
    await loginAs(page, 'SELLER')
    const listResponse = page.waitForResponse((response) => response.url().endsWith('/api/v1/seller/bank-accounts') && response.request().method() === 'GET')
    const meResponse = page.waitForResponse((response) => response.url().endsWith('/api/v1/seller/me'))
    await gotoBankAccount(page)
    const response = await listResponse
    const me = (await (await meResponse).json()) as { roleCode: string }
    expect(response.status()).toBe(200)
    const accounts = (await response.json()) as { accountNumberSuffix: string; isPrimary: boolean }[]

    const sidebar = page.getByTestId('seller-sidebar')
    await expect(sidebar.locator('a[href="/seller/settings/bank-account"]')).toHaveClass(/v-list-item--active/)
    if (accounts.length === 0) {
      await expect(page.getByTestId('seller-bank-account-empty')).toBeVisible()
    } else {
      await expect(page.getByTestId('seller-bank-account-rows').locator('.v-list-item')).toHaveCount(accounts.length)
      await expect(page.getByTestId('seller-bank-account-primary')).toHaveCount(accounts.filter((account) => account.isPrimary).length)
      for (const account of accounts) {
        await expect(page.getByTestId('seller-bank-account-rows')).toContainText(`···${account.accountNumberSuffix}`)
        expect(account).not.toHaveProperty('accountNumber')
      }
    }
    // 폼 표시 여부는 실 /seller/me 응답(roleCode)과 대조한다(데모 셀러는 OWNER·POST는 실행하지 않는다)
    if (me.roleCode === 'SELLER_OWNER') {
      await expect(page.getByTestId('seller-bank-account-form-card')).toBeVisible()
      await expect(page.getByTestId('seller-bank-account-form-notice')).toContainText('주 정산계좌로 지정')
      await expect(page.getByTestId('seller-bank-account-submit')).toBeEnabled()
    }
    await expect(page.getByTestId('seller-bank-account-readonly-notice')).toHaveCount(me.roleCode === 'SELLER_OWNER' ? 0 : 1)
  })

  test('② STAFF(me mock) → 등록 폼 없음·조회 전용 안내 · 목록 mock 2건(주 계좌 칩 1·은행 표시명·끝 4자리)', async ({ page }) => {
    await loginAs(page, 'SELLER')
    await mockSellerMe(page, { roleCode: 'SELLER_STAFF' })
    await page.route((url) => url.pathname.endsWith('/api/v1/seller/bank-accounts'), (route) => route.fulfill({ json: ACCOUNTS }))
    await gotoBankAccount(page)

    await expect(page.getByTestId('seller-bank-account-form-card')).toHaveCount(0)
    await expect(page.getByTestId('seller-bank-account-readonly-notice')).toContainText('셀러 대표(OWNER)만')
    await expect(page.getByTestId('seller-bank-account-rows').locator('.v-list-item')).toHaveCount(2)
    await expect(page.getByTestId('seller-bank-account-primary')).toHaveCount(1)
    await expect(page.getByTestId('seller-bank-account-row-1')).toContainText('KB국민은행 ···6789')
    await expect(page.getByTestId('seller-bank-account-row-2')).toContainText('신한은행 ···4321')
  })
})
