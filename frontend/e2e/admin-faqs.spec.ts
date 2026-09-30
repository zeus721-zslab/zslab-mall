import { test, expect, type Browser, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 FAQ 관리(Track 106-3) E2E — 실 BE(mock 없음). 등록 → 구매자 도우미에 보임 → 수정(공개 끔) → 구매자 도우미에서 사라짐 → 정렬(위로 → 아래로 원복)
 * → 삭제. V42 기본 FAQ가 상주하므로 이 테스트가 만든 행(질문이 MARKER로 시작)만 단언하고, 남은 행은 afterEach에서 API로 지운다.
 * 스크린샷 → playwright-report/track-106-3(docs/frontend/screens-track-106-3로 옮긴다).
 */
const SCREENSHOT_DIR = 'playwright-report/track-106-3'
const MARKER = 'E2E106-3 도우미 확인용'
const CATEGORY = 'CLAIM'
const CATEGORY_LABEL = '취소·반품·교환'
const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
const XSRF_HEADER_NAME = 'X-XSRF-TOKEN'

interface AdminFaqRow {
  id: number
  question: string
}

/** 이 테스트가 만든 행을 API로 지운다(실패로 중간에 멈춘 경우 대비). */
async function removeMarkedRows(page: Page): Promise<void> {
  const response = await page.request.get('/api/v1/admin/faqs')
  if (!response.ok()) return
  const rows = (await response.json()) as AdminFaqRow[]
  const token = (await page.context().cookies()).find((cookie) => cookie.name === XSRF_COOKIE_NAME)?.value ?? ''
  for (const row of rows.filter((item) => item.question.startsWith(MARKER))) {
    await page.request.delete(`/api/v1/admin/faqs/${row.id}`, { headers: { [XSRF_HEADER_NAME]: token } })
  }
}

/** 새 구매자 페이지(비로그인)에서 도우미를 열고 카테고리를 눌러 질문 칩 문구를 돌려준다. */
async function buyerQuestionChips(browser: Browser, baseURL: string | undefined): Promise<string[]> {
  const buyerContext = await browser.newContext({ baseURL })
  const buyerPage = await buyerContext.newPage()
  try {
    await buyerPage.goto('/')
    await buyerPage.getByTestId('faq-launcher').click()
    await buyerPage.getByTestId('faq-category-chip').filter({ hasText: CATEGORY_LABEL }).click()
    await expect(buyerPage.getByTestId('faq-bubble').last()).toContainText(`${CATEGORY_LABEL} 관련 자주 묻는 질문이에요.`)
    return await buyerPage.getByTestId('faq-chip').allTextContents()
  } finally {
    await buyerContext.close()
  }
}

test.describe('관리자 FAQ 관리(Track 106-3)', () => {
  test.afterEach(async ({ page }) => {
    await removeMarkedRows(page)
  })

  test('등록 → 구매자 노출 → 공개 끔 → 구매자 미노출 → 위/아래 정렬 → 삭제', async ({ page, browser, baseURL }) => {
    const question = `${MARKER} ${Date.now()}`
    await loginAs(page, 'ADMIN')
    await removeMarkedRows(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/faqs')
    await page.locator(`[data-testid="admin-faq-category"][data-category="${CATEGORY}"]`).click()
    const table = page.getByTestId('admin-faq-table')
    await expect(table.getByTestId('row-question').first()).toBeVisible()
    const rowCountBefore = await table.getByTestId('row-question').count()

    // 등록: 현재 카테고리가 기본값 · 끝에 붙는다
    await page.getByTestId('go-create').click()
    const dialog = page.getByTestId('admin-faq-dialog')
    await dialog.getByTestId('faq-question').locator('input').fill(`  ${question}  `)
    await dialog.getByTestId('faq-answer').locator('textarea').first().fill('E2E 확인용 답변입니다.')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-faq-dialog.png` })
    await dialog.getByTestId('faq-dialog-ok').click()
    await expect(dialog).toHaveCount(0)
    await expect(table.getByTestId('row-question')).toHaveCount(rowCountBefore + 1)
    await expect(table.getByTestId('row-question').last()).toHaveText(question)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-faq-list.png`, fullPage: true })
    expect(await buyerQuestionChips(browser, baseURL)).toContain(question)

    // 수정: 공개 끔 → 숨김 배지 · 구매자 도우미에서 빠짐
    const myRow = table.locator('tr', { hasText: question })
    await myRow.getByTestId('row-edit').click()
    await dialog.getByTestId('faq-visible').locator('input').uncheck()
    await dialog.getByTestId('faq-dialog-ok').click()
    await expect(dialog).toHaveCount(0)
    await expect(myRow.getByTestId('row-visible')).toHaveText('숨김')
    expect(await buyerQuestionChips(browser, baseURL)).not.toContain(question)

    // 정렬: 위로 한 칸 → 아래로 한 칸(기본 FAQ 순서 원복)
    await myRow.getByTestId('row-move-up').click()
    await expect(table.getByTestId('row-question').nth(rowCountBefore - 1)).toHaveText(question)
    await myRow.getByTestId('row-move-down').click()
    await expect(table.getByTestId('row-question').last()).toHaveText(question)

    // 삭제
    await myRow.getByTestId('row-delete').click()
    const confirm = page.getByTestId('admin-faq-delete-dialog')
    await expect(confirm).toContainText(question)
    await confirm.getByRole('button', { name: '삭제' }).click()
    await expect(table.getByTestId('row-question')).toHaveCount(rowCountBefore)
    await expect(table.locator('tr', { hasText: question })).toHaveCount(0)
  })
})
