import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedRows } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 1:1 문의·상품 Q&A·리뷰·FAQ. 다이얼로그는 열기만 하고 확인 버튼은 누르지 않는다. FAQ 순서 화살표는 누르는 즉시 저장된다.
 * 영역 키는 layers/admin/app/lib/admin-manual/qna-inquiry.ts · review.ts · faq.ts와 1:1이다.
 * 데이터 전제: 미답변 1:1 문의(seed.py inquiries) · 공개 상품 질문(qna) · 공개 리뷰(reviews) · FAQ(V42).
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('1:1 문의 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inquiries')
  await waitScreen(page, 'admin-inquiry-table')
  const row = seedRows(page.getByTestId('admin-inquiry-table').locator('tbody tr')).first()
  await capture.shot('inquiry-list', {
    answered: page.getByTestId('filter-answered'),
    category: page.getByTestId('filter-category'),
    answer: row.getByTestId('row-answer'),
  })
})

test('문의 답변 다이얼로그(답안 초안)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inquiries')
  await waitScreen(page, 'admin-inquiry-table')
  await seedRows(page.getByTestId('admin-inquiry-table').locator('tbody tr')).first().getByTestId('row-answer').click()
  const dialog = page.getByTestId('admin-inquiry-answer-dialog')
  await expect(dialog).toBeVisible()
  // 초안은 다이얼로그를 연 뒤 따로 계산된다(AnswerDraftBox) — 계산이 끝날 때까지 기다린다.
  await expect(dialog.getByTestId('answer-draft-loading')).toHaveCount(0)
  await capture.shot('inquiry-answer', {
    question: dialog.getByTestId('answer-inquiry'),
    draft: dialog.getByTestId('answer-draft-box'),
    content: dialog.getByTestId('answer-content'),
    confirm: dialog.getByTestId('answer-dialog-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('answer-dialog-close').click()
})

test('상품 Q&A 숨김', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products/questions?status=VISIBLE')
  await waitScreen(page, 'admin-question-table')
  const row = seedRows(page.getByTestId('admin-question-table').locator('tbody tr')).first()
  await capture.shot('question-list', {
    status: page.getByTestId('filter-status'),
    rowStatus: row.getByTestId('row-status'),
    change: row.getByTestId('row-change-status'),
  })
})

test('리뷰 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products/reviews?status=VISIBLE')
  await waitScreen(page, 'admin-review-table')
  const row = seedRows(page.getByTestId('admin-review-table').locator('tbody tr')).first()
  await capture.shot('review-list', {
    status: page.getByTestId('filter-status'),
    rating: row.getByTestId('row-rating'),
    change: row.getByTestId('row-change-status'),
  })
})

test('리뷰 숨김 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products/reviews?status=VISIBLE')
  await waitScreen(page, 'admin-review-table')
  await seedRows(page.getByTestId('admin-review-table').locator('tbody tr')).first().getByTestId('row-change-status').click()
  const dialog = page.getByTestId('admin-review-status-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('review-hide', {
    target: dialog.getByTestId('review-status-target'),
    message: dialog.getByTestId('review-status-message'),
    reason: dialog.getByTestId('review-status-reason'),
    confirm: dialog.getByTestId('review-status-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('review-status-cancel').click()
})

test('FAQ 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/faqs')
  await waitScreen(page, 'admin-faq-table')
  const row = page.getByTestId('admin-faq-table').locator('tbody tr').first()
  await capture.shot('faq-list', {
    create: page.getByTestId('go-create'),
    categories: page.getByTestId('admin-faq-categories'),
    order: row.getByTestId('row-move-down'),
    visible: row.getByTestId('row-visible'),
    delete: row.getByTestId('row-delete'),
  })
})

test('FAQ 등록 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/faqs')
  await waitScreen(page, 'admin-faq-table')
  await page.getByTestId('go-create').click()
  const dialog = page.getByTestId('admin-faq-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('faq-edit', {
    category: dialog.getByTestId('faq-category'),
    question: dialog.getByTestId('faq-question'),
    answer: dialog.getByTestId('faq-answer'),
    visible: dialog.getByTestId('faq-visible'),
  }, { focus: dialog })
  await dialog.getByTestId('faq-dialog-cancel').click()
})
