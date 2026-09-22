import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 8(Track 99): 배송 관리에서 배송중 배송 1건의 송장번호를 정정한다.
 * 완료 조건 = 배송 목록 해당 행의 송장번호가 새 값으로 바뀐다.
 *
 * 대시보드에 배송 관리 진입점이 없어 사이드바로 이동한다. 고른 주문번호는 관찰값으로 남겨 리포트에서 시나리오 간 중복 여부를 확인한다.
 */
test('관리자 · 배송 목록 → 송장번호 정정', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'delivery-tracking-fix', '배송 목록 → 송장번호 정정')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  walkthrough.note('대시보드 진입점', '없음(배송 관리 타일 미제공)')
  await walkthrough.shot('대시보드-배송관리-진입점-없음')

  await walkthrough.click(page.getByTestId('admin-sidebar').locator('a[href="/admin/orders/deliveries"]'))
  await waitScreen(page, 'admin-delivery-table')
  await walkthrough.shot('배송-목록')

  // 필터 적용은 비동기라 직전 목록이 잠깐 남는다. 행 수가 바뀔 때까지 기다려야 어느 행을 고르는지가 실행마다 같아진다.
  const rows = page.getByTestId('admin-delivery-table').locator('tbody tr')
  const unfilteredCount = await rows.count()
  await walkthrough.select(page.getByTestId('filter-status'), '배송중')
  await expect(rows).not.toHaveCount(unfilteredCount)
  // 필터 적용 전 표를 잡지 않도록 행 자체에 '배송중' 상태 배지를 조건으로 건다.
  // 수령인 '워크스루'는 prepare.py가 만든 데모 구매자 주문이다 — buyer-order-tracking이 그 배송을 관찰하므로 정정 대상에서 뺀다.
  const shippingRows = page.getByTestId('admin-delivery-table').locator('tbody tr')
    .filter({ has: page.getByTestId('status-chip').filter({ hasText: '배송중' }) })
    .filter({ hasNotText: '워크스루' })
  await expect(shippingRows.first()).toBeVisible()
  await walkthrough.shot('배송중-필터-적용')

  // 마지막 행은 표가 다시 그려질 때 내용이 흔들려 실행마다 달라졌다(1·2회차 관찰값 불일치) — 첫 행으로 고정한다.
  // 직전 시나리오(admin-delivery-complete)가 이미 배송중 1건을 배송완료로 뺐고, 셀러 시나리오는 이 시나리오가 붙인 'WTA' 송장을 제외하므로 행이 겹치지 않는다.
  const row = shippingRows.first()
  await expect(row).toBeVisible()
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  const beforeTrackingNo = (await row.getByTestId('row-tracking-no').innerText()).trim()
  walkthrough.note('정정 대상 주문번호', orderNo)
  walkthrough.note('정정 전 송장번호', beforeTrackingNo)

  await walkthrough.click(row)
  await expect(page.getByTestId('admin-delivery-detail-dialog')).toBeVisible()
  await walkthrough.shot('배송-상세-다이얼로그')

  await walkthrough.click(page.getByTestId('delivery-correct-tracking'))
  await expect(page.getByTestId('admin-delivery-tracking-dialog')).toBeVisible()
  const trackingNo = 'WTA' + Date.now()
  await walkthrough.select(page.getByTestId('tracking-carrier'), '한진택배')
  await walkthrough.fill(page.getByTestId('tracking-no').locator('input').first(), trackingNo)
  await walkthrough.fill(page.getByTestId('tracking-reason').locator('textarea').first(), '워크스루 송장 오입력 정정')
  await walkthrough.shot('송장정정-입력')

  await walkthrough.click(page.getByTestId('tracking-dialog-ok'))
  await expect(page.getByTestId('admin-delivery-tracking-dialog')).toBeHidden()
  // 완료 조건: 목록 행의 송장번호가 새 값으로 바뀐다.
  await expect(page.getByTestId('admin-delivery-table').getByText(trackingNo, { exact: true })).toBeVisible()
  await walkthrough.shot('송장정정-반영')

  walkthrough.finish()
})
