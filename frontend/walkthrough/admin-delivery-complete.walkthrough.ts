import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 7(Track 99): 배송중 품목을 배송완료로 처리한다.
 * 완료 조건 = 처리한 주문이 배송상태 '배송중' 필터 목록에서 사라진다.
 *
 * 대시보드에 배송중 진입점이 없어(PENDING_TILES 7칸에 배송 관련은 '배송 대기'=PAID뿐) 사이드바 주문 관리 → 전체 주문으로 간 뒤 배송상태 필터를 건다.
 * 대상 행은 구매자 워크스루 시나리오(buyer-order-tracking)가 쓰는 데모 구매자 주문을 피해 고른다.
 */
const BUYER_EMAIL = process.env.BUYER_E2E_EMAIL ?? ''

test('관리자 · 배송중 품목 배송완료 처리', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'delivery-complete', '배송중 품목 → 배송완료 처리')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  walkthrough.note('대시보드 진입점', '없음(배송중 타일 미제공 · 배송 대기=결제완료 타일만 있음)')
  await walkthrough.shot('대시보드-배송중-진입점-없음')

  await walkthrough.click(page.getByTestId('admin-sidebar').locator('a[href="/admin/orders"]'))
  await waitScreen(page, 'admin-order-table')
  await walkthrough.shot('주문-목록')

  // 필터 적용은 비동기라 직전 목록이 잠깐 남는다. 행 수가 바뀔 때까지 기다려야 어느 행을 고르는지가 실행마다 같아진다.
  const rows = page.getByTestId('admin-order-table').locator('tbody tr')
  const unfilteredCount = await rows.count()
  await walkthrough.select(page.getByTestId('filter-delivery-status'), '배송중')
  await expect(rows).not.toHaveCount(unfilteredCount)
  // 필터 적용 전 표(직전 목록)를 잡지 않도록 대상 행 자체에 주문상태 '배송중'을 조건으로 건다(배송중 주문 = SHIPPING 배송 보유 = 배송완료 처리 가능).
  // 배송 배지(delivery-status-chip)는 주문상태와 라벨이 같으면 숨겨지므로(showDeliveryChip) 조건으로 쓸 수 없다.
  const row = page.getByTestId('admin-order-table').locator('tbody tr')
    .filter({ hasNotText: BUYER_EMAIL })
    .filter({ has: page.getByTestId('status-chip').filter({ hasText: '배송중' }) })
    .filter({ has: page.getByTestId('row-menu') }).first()
  await expect(row).toBeVisible()
  await walkthrough.shot('배송중-필터-적용')
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  walkthrough.note('배송완료 처리 주문번호', orderNo)

  await walkthrough.click(row.getByTestId('row-menu'))
  await walkthrough.shot('행-메뉴')
  await walkthrough.click(page.getByTestId('row-mark-delivered'))
  await expect(page.getByTestId('admin-mark-delivered-dialog')).toBeVisible()
  await walkthrough.shot('배송완료-다이얼로그')

  await walkthrough.click(page.getByTestId('delivered-dialog-ok'))
  await expect(page.getByTestId('admin-mark-delivered-dialog')).toBeHidden()
  // 완료 조건: 배송완료 처리한 주문은 배송중 필터 목록에서 빠진다.
  await expect(page.getByTestId('admin-order-table').getByText(orderNo, { exact: true })).toHaveCount(0)
  await walkthrough.shot('배송완료-후-목록')

  walkthrough.finish()
})
