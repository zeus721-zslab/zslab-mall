import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 4(Track 99): 배송 화면에서 배송중 배송 1건의 송장번호를 정정한다.
 * 완료 조건 = 배송 목록 해당 행의 송장번호가 새 값으로 바뀐다.
 *
 * 대시보드 4칸에 배송 화면 진입점이 없어(배송 대기 칸은 주문 화면으로 간다) 사이드바로 이동한다.
 */
test('셀러 · 배송 목록 → 송장번호 정정', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'delivery-tracking-fix', '배송 목록 → 송장번호 정정')
  await loginAs(page, 'SELLER')

  await walkthrough.goto('/seller')
  await waitScreen(page, 'seller-dashboard')
  walkthrough.note('대시보드 진입점', '없음(배송 대기 칸은 주문 화면으로 이동 · 배송 화면 칸 미제공)')
  await walkthrough.shot('대시보드-배송화면-진입점-없음')

  await walkthrough.click(page.getByTestId('seller-sidebar').locator('a[href="/seller/deliveries"]'))
  await waitScreen(page, 'seller-delivery-table')
  await walkthrough.shot('배송-목록')

  // 필터 적용은 비동기라 직전 목록이 잠깐 남는다. 행 수가 바뀔 때까지 기다려야 어느 행을 고르는지가 실행마다 같아진다.
  const rows = page.getByTestId('seller-delivery-table').locator('tbody tr')
  const unfilteredCount = await rows.count()
  await walkthrough.select(page.getByTestId('filter-status'), '배송중')
  await expect(rows).not.toHaveCount(unfilteredCount)
  // 필터 적용 전 표를 잡지 않도록 행 자체에 '배송중' 상태 배지를 조건으로 건다.
  // 'WTA'는 관리자 송장 정정 시나리오(admin-delivery-tracking-fix)가 붙이는 접두사다 — 같은 배송을 두 번 정정하지 않도록 제외한다.
  const row = page.getByTestId('seller-delivery-table').locator('tbody tr')
    .filter({ has: page.getByTestId('status-chip').filter({ hasText: '배송중' }) })
    .filter({ hasNotText: 'WTA' })
    .filter({ has: page.getByTestId('row-menu') }).first()
  await expect(row).toBeVisible()
  await walkthrough.shot('배송중-필터-적용')
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  walkthrough.note('정정 대상 주문번호', orderNo)

  await walkthrough.click(row.getByTestId('row-menu'))
  await walkthrough.shot('행-메뉴')
  await walkthrough.click(page.getByTestId('row-correct-tracking'))
  await expect(page.getByTestId('seller-tracking-dialog')).toBeVisible()

  const trackingNo = 'WTS' + Date.now()
  await walkthrough.select(page.getByTestId('tracking-carrier'), '한진택배')
  await walkthrough.fill(page.getByTestId('tracking-no').locator('input').first(), trackingNo)
  await walkthrough.fill(page.getByTestId('tracking-reason').locator('textarea').first(), '워크스루 송장 오입력 정정')
  await walkthrough.shot('송장정정-입력')

  await walkthrough.click(page.getByTestId('tracking-dialog-ok'))
  await expect(page.getByTestId('seller-tracking-dialog')).toBeHidden()
  // 완료 조건: 목록 행의 송장번호가 새 값으로 바뀐다.
  await expect(page.getByTestId('seller-delivery-table').getByText(trackingNo, { exact: true })).toBeVisible()
  await walkthrough.shot('송장정정-반영')

  walkthrough.finish()
})
