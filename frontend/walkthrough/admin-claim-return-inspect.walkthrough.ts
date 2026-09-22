import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 4(Track 99·역할 전환): 구매자가 반품 회수 송장을 등록하고, 관리자가 대시보드에서 들어가 회수 확인 + 검수 합격(재입고)까지 처리한다.
 * 완료 조건 = 검수한 클레임이 클레임 처리 대기(action=FOLLOWUP) 목록에서 사라진다(합격 → Mock 환불 자동 완료).
 *
 * 역할 전환은 같은 브라우저 컨텍스트에 역할별 쿠키(path가 / · /admin으로 갈려 충돌하지 않는다)를 심어 구현하며, 계측은 segment로 역할별로 나눈다.
 */
const BUYER_EMAIL = process.env.BUYER_E2E_EMAIL ?? ''

test('관리자 · 반품 회수 송장(구매자) → 검수 합격·재입고', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'claim-return-inspect', '반품 회수 송장(구매자) → 검수 합격')

  // ---------- 구매자: 회수 송장 등록 ----------
  walkthrough.segment('구매자', 'buyer')
  await loginAs(page, 'BUYER')
  await walkthrough.goto('/claims')
  await expect(page.getByRole('heading', { name: '취소·반품·교환 내역' })).toBeVisible()
  // hydration 전에 링크를 누르면 NuxtLink가 아닌 네이티브 이동이 돼 상세가 SSR로 다시 그려지고, 이어지는 select 조작이 hydration에 덮인다.
  await page.waitForLoadState('networkidle')
  await walkthrough.shot('구매자-클레임-목록')

  // 유형만으로는 과거 완료 건과 섞이므로 상태(승인 = 회수 송장 등록 단계)까지 함께 건다.
  const returnClaim = page.locator('a[href^="/claims/clm_"]')
    .filter({ hasText: '반품' }).filter({ hasText: '승인' }).first()
  await expect(returnClaim).toBeVisible()
  await walkthrough.click(returnClaim)
  // hydration이 끝나기 전에 select를 건드리면 v-model 바인딩 전이라 값이 사라진다 — 네트워크가 잠잠해질 때까지 기다린다.
  await page.waitForLoadState('networkidle')
  await expect(page.getByTestId('claim-return-shipment-form')).toBeVisible()
  await walkthrough.shot('구매자-회수송장-폼')

  await walkthrough.selectOption(page.locator('#shipmentCarrier'), 'CJ')
  // 송장번호는 UNIQUE(DLV-1)라 실행 시각으로 만든다.
  await walkthrough.fill(page.locator('#shipmentTrackingNo'), 'WTR' + Date.now())
  await expect(page.locator('#shipmentCarrier')).toHaveValue('CJ')
  await walkthrough.click(page.getByTestId('claim-return-shipment-submit'))
  await expect(page.getByTestId('claim-return-shipment')).toBeVisible()
  await walkthrough.shot('구매자-회수송장-등록완료')

  // ---------- 관리자: 회수 확인 + 검수 ----------
  walkthrough.segment('관리자', 'admin')
  await loginAs(page, 'ADMIN')
  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.shot('대시보드-클레임처리대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-claimFollowup'))
  await waitScreen(page, 'admin-claim-table')
  await walkthrough.shot('클레임-처리대기-목록')

  const row = page.getByTestId('admin-claim-table').locator('tbody tr')
    .filter({ hasText: BUYER_EMAIL }).filter({ hasText: '반품' })
    .filter({ has: page.getByTestId('row-inspect') }).first()
  await expect(row).toBeVisible()
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  walkthrough.note('검수 대상 주문번호', orderNo)

  await walkthrough.click(row.getByTestId('row-inspect'))
  await expect(page.getByTestId('admin-claim-inspect-dialog')).toBeVisible()
  await walkthrough.shot('검수-다이얼로그')

  await walkthrough.check(page.getByTestId('inspect-pickup-check').locator('input').first())
  await walkthrough.click(page.getByTestId('inspect-result-PASS'))
  await walkthrough.click(page.getByTestId('inspect-restock-true'))
  await walkthrough.shot('검수-합격-재입고-선택')

  await walkthrough.click(page.getByTestId('inspect-dialog-ok'))
  await expect(page.getByTestId('admin-claim-inspect-dialog')).toBeHidden()
  // 완료 조건: 검수를 마친 클레임은 처리 대기 목록에서 빠진다.
  await expect(page.getByTestId('admin-claim-table').getByText(orderNo, { exact: true })).toHaveCount(0)
  await walkthrough.shot('검수-후-목록')

  walkthrough.finish()
})
