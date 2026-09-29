import { test, expect } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 리뷰 관리(Track 106-1 PR2) E2E. 관리자 레이어는 ssr:false라 page.goto로도 page.route mock이 적용된다(admin-claims 패턴).
 * 목록(상품 · 별점 · 본문 발췌 · 사진 수 · 상태 · 숨김 사유) · 숨김 리뷰 사진은 "사진 N장(비공개)"만 · 숨김 모달(사유 필수) → PATCH 본문 → 목록 재조회.
 */
const VISIBLE_REVIEW = 'rvw_E2E00000000000000000001091'
const HIDDEN_REVIEW = 'rvw_E2E00000000000000000001092'
const SCREENSHOT_DIR = 'playwright-report/track-106-1'

const ROWS = [
  {
    reviewId: VISIBLE_REVIEW, productPublicId: 'prd_E2E1', productName: 'E2E 린넨 셔츠', rating: 1, content: '지금 바로 이 링크로 오세요 — 광고 문구 예시입니다.',
    optionLabel: '색상: 블랙', status: 'VISIBLE', helpfulCount: 0,
    photoUrls: ['/api/v1/files/reviews/2026/09/E2E-A.png', '/api/v1/files/reviews/2026/09/E2E-B.png'], createdAt: '2026-09-29T10:00:00.000+09:00',
  },
  {
    reviewId: HIDDEN_REVIEW, productName: undefined, rating: 2, content: '숨겨진 리뷰', status: 'HIDDEN', hiddenReason: '욕설 포함', helpfulCount: 1,
    photoUrls: ['/api/v1/files/reviews/2026/09/E2E-C.png'], createdAt: '2026-09-28T10:00:00.000+09:00',
  },
]

test.describe('관리자 리뷰 관리(Track 106-1)', () => {
  test('목록 표시 · 숨김 사진은 장수만 · 숨김 모달 사유 필수 → PATCH {status, reason} → 목록 재조회', async ({ page }) => {
    const listCalls: string[] = []
    const patches: { url: string; body: unknown }[] = []
    await page.route((url) => url.pathname === '/api/v1/admin/reviews', (route) => {
      listCalls.push(route.request().url())
      return route.fulfill({ json: { items: ROWS, page: 0, size: 20, totalCount: ROWS.length, hasNext: false } })
    })
    await page.route((url) => /\/api\/v1\/admin\/reviews\/rvw_[^/]+\/status$/.test(url.pathname), (route) => {
      patches.push({ url: route.request().url(), body: JSON.parse(route.request().postData() ?? '{}') })
      return route.fulfill({ status: 204, body: '' })
    })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/reviews')

    const table = page.getByTestId('admin-review-table')
    await expect(table.getByTestId('row-product')).toHaveText(['E2E 린넨 셔츠', '삭제된 상품'])
    await expect(table.getByTestId('row-photos')).toHaveText(['사진 2장', '사진 1장(비공개)'])
    await expect(table.getByTestId('row-hidden-reason')).toHaveText(['—', '욕설 포함'])
    await expect(table.getByTestId('row-status')).toHaveText(['공개', '숨김'])
    await expect(table.getByTestId('row-change-status')).toHaveText(['숨김', '숨김 해제'])
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-reviews-desktop.png`, fullPage: true })

    await table.getByTestId('row-change-status').first().click()
    const dialog = page.getByTestId('admin-review-status-dialog')
    await expect(dialog).toContainText('리뷰 숨김')
    await expect(dialog.getByTestId('review-status-ok')).toBeDisabled()
    await dialog.getByRole('textbox', { name: '사유 (필수)' }).fill('  광고성 게시물  ')
    await expect(dialog.getByTestId('review-status-ok')).toBeEnabled()
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-reviews-hide-dialog.png` })
    const callsBefore = listCalls.length
    await dialog.getByTestId('review-status-ok').click()

    await expect(dialog).toHaveCount(0)
    expect(patches).toEqual([{ url: expect.stringContaining(`/api/v1/admin/reviews/${VISIBLE_REVIEW}/status`), body: { status: 'HIDDEN', reason: '광고성 게시물' } }])
    await expect.poll(() => listCalls.length).toBeGreaterThan(callsBefore)
  })

  test('상태 필터 → URL query · API status 파라미터', async ({ page }) => {
    const listCalls: URL[] = []
    await page.route((url) => url.pathname === '/api/v1/admin/reviews', (route) => {
      listCalls.push(new URL(route.request().url()))
      return route.fulfill({ json: { items: [ROWS[1]], page: 0, size: 20, totalCount: 1, hasNext: false } })
    })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/reviews?status=HIDDEN')
    await expect(page.getByTestId('admin-review-table').getByTestId('row-status')).toHaveText(['숨김'])
    expect(listCalls.at(-1)!.searchParams.get('status')).toBe('HIDDEN')
  })
})
