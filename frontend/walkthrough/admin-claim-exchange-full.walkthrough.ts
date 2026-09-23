import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 5(Track 99·역할 전환): 교환 요청을 승인하고, 구매자가 회수 송장을 등록한 뒤, 관리자가 검수 → 교환품 발송 → 배송완료까지 끝낸다.
 * 완료 조건 = 교환 클레임이 클레임 처리 대기(action=FOLLOWUP) 목록에서 사라진다(교환 완료).
 *
 * 계측은 segment로 관리자(승인) · 구매자(회수 송장) · 관리자(검수~배송완료) 3구간으로 나눈다.
 */
const BUYER_EMAIL = process.env.BUYER_E2E_EMAIL ?? ''

test('관리자 · 교환 승인 → 회수 송장(구매자) → 검수 → 교환품 발송 → 배송완료', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'claim-exchange-full', '교환 승인 → 검수 → 발송 → 배송완료')

  // ---------- 관리자: 교환 승인 ----------
  walkthrough.segment('관리자·승인', 'admin')
  await loginAs(page, 'ADMIN')
  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.shot('대시보드-클레임요청-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-claimRequested'))
  await waitScreen(page, 'admin-claim-table')
  await walkthrough.click(page.getByTestId('claim-tab-EXCHANGE'))
  const approveRow = page.getByTestId('admin-claim-table').locator('tbody tr')
    .filter({ hasText: BUYER_EMAIL }).filter({ has: page.getByTestId('row-approve') }).first()
  await expect(approveRow).toBeVisible()
  const orderNo = (await approveRow.getByTestId('row-order-no').innerText()).trim()
  walkthrough.note('교환 대상 주문번호', orderNo)
  await walkthrough.shot('교환-탭-대상-행')

  await walkthrough.click(approveRow.getByTestId('row-approve'))
  await expect(page.getByTestId('admin-claim-approve-dialog')).toBeVisible()
  await walkthrough.shot('승인-확인-다이얼로그')
  await walkthrough.click(page.getByTestId('admin-claim-approve-dialog-ok'))
  await expect(page.getByTestId('admin-claim-approve-dialog')).toBeHidden()
  await walkthrough.shot('승인-후-목록')

  // ---------- 구매자: 회수 송장 등록 ----------
  walkthrough.segment('구매자·회수송장', 'buyer')
  await loginAs(page, 'BUYER')
  await walkthrough.goto('/orders?tab=exchange')
  await expect(page.getByRole('heading', { name: '주문 내역' })).toBeVisible()
  // hydration 전에 링크를 누르면 NuxtLink가 아닌 네이티브 이동이 돼 상세가 SSR로 다시 그려지고, 이어지는 select 조작이 hydration에 덮인다.
  await page.waitForLoadState('networkidle')
  // 유형만으로는 과거 완료 건과 섞이므로 상태(승인 = 회수 송장 등록 단계)까지 함께 건다.
  const exchangeClaim = page.locator('a[href^="/claims/clm_"]')
    .filter({ hasText: '교환' }).filter({ hasText: '승인' }).first()
  await expect(exchangeClaim).toBeVisible()
  await walkthrough.click(exchangeClaim)
  // hydration이 끝나기 전에 select를 건드리면 v-model 바인딩 전이라 값이 사라진다 — 네트워크가 잠잠해질 때까지 기다린다.
  await page.waitForLoadState('networkidle')
  await expect(page.getByTestId('claim-return-shipment-form')).toBeVisible()
  await walkthrough.shot('구매자-회수송장-폼')

  await walkthrough.selectOption(page.locator('#shipmentCarrier'), 'CJ')
  await walkthrough.fill(page.locator('#shipmentTrackingNo'), 'WTX' + Date.now())
  await expect(page.locator('#shipmentCarrier')).toHaveValue('CJ')
  await walkthrough.click(page.getByTestId('claim-return-shipment-submit'))
  await expect(page.getByTestId('claim-return-shipment')).toBeVisible()
  await walkthrough.shot('구매자-회수송장-등록완료')

  // ---------- 관리자: 검수 → 교환품 발송 → 배송완료 ----------
  walkthrough.segment('관리자·검수~배송완료', 'admin')
  await loginAs(page, 'ADMIN')
  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.click(page.getByTestId('dashboard-pending-claimFollowup'))
  await waitScreen(page, 'admin-claim-table')
  await walkthrough.shot('클레임-처리대기-목록')

  const followupRow = page.getByTestId('admin-claim-table').locator('tbody tr').filter({ hasText: orderNo }).first()
  await expect(followupRow).toBeVisible()
  await walkthrough.click(followupRow.getByTestId('row-inspect'))
  await expect(page.getByTestId('admin-claim-inspect-dialog')).toBeVisible()
  await walkthrough.check(page.getByTestId('inspect-pickup-check').locator('input').first())
  await walkthrough.click(page.getByTestId('inspect-result-PASS'))
  await walkthrough.click(page.getByTestId('inspect-restock-true'))
  await walkthrough.shot('검수-합격-선택')
  await walkthrough.click(page.getByTestId('inspect-dialog-ok'))
  await expect(page.getByTestId('admin-claim-inspect-dialog')).toBeHidden()

  // Track 99 FE-61: 교환 검수 합격이면 목록 갱신 후 교환품 발송 다이얼로그가 이어서 열린다(행을 다시 찾아 누르지 않는다).
  await expect(page.getByTestId('admin-exchange-shipment-dialog')).toBeVisible()
  await walkthrough.shot('교환품-발송-다이얼로그-자동-열림')
  await walkthrough.select(page.getByTestId('exchange-shipment-carrier'), 'CJ대한통운')
  await walkthrough.fill(page.getByTestId('exchange-shipment-tracking-no').locator('input').first(), 'WTE' + Date.now())
  await walkthrough.shot('교환품-발송-입력')
  await walkthrough.click(page.getByTestId('exchange-shipment-ok'))
  await expect(page.getByTestId('admin-exchange-shipment-dialog')).toBeHidden()

  const deliveredRow = page.getByTestId('admin-claim-table').locator('tbody tr').filter({ hasText: orderNo }).first()
  await expect(deliveredRow.getByTestId('row-mark-exchange-delivered')).toBeVisible()
  await walkthrough.click(deliveredRow.getByTestId('row-mark-exchange-delivered'))
  await expect(page.getByTestId('exchange-delivered-dialog')).toBeVisible()
  await walkthrough.shot('교환품-배송완료-다이얼로그')
  await walkthrough.click(page.getByTestId('exchange-delivered-dialog-ok'))
  // 완료 조건: 교환이 끝난 클레임은 처리 대기 목록에서 빠진다.
  await expect(page.getByTestId('admin-claim-table').getByText(orderNo, { exact: true })).toHaveCount(0)
  await walkthrough.shot('교환-완료-후-목록')

  walkthrough.finish()
})
