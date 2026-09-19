import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 셀러 관리(FE-40·Track 89-D) E2E 스모크. 로그인은 데모 버튼(NUXT_ADMIN_DEMO_* 주입 환경·미주입 시 skip), 목록·상세·회원 검색은 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(목록 → 승인 대기 배너·배지 → 상세(차단 사유·종료 비활성 툴팁·구성원 0 경고) → 정지 다이얼로그(사유 필수)
 * → 수정 다이얼로그(율 경고) → 입점 다이얼로그(검색·선택·폼)까지. PATCH·PUT·POST는 호출하지 않는다). ②(FE-41)는 정산계좌 카드(목록·끝 4자리·주 계좌 배지)
 * → 등록 다이얼로그(첫 계좌 안내 없음·계좌번호 검증) → 수정 다이얼로그(기존 번호 미표시·사유 필수) → 주 계좌 전환 다이얼로그(안내 3문장·주 계좌 행은 비활성)까지.
 */
const SELLER_A = 'slr_E2E0000000000000000000SA1'
const SELLER_P = 'slr_E2E0000000000000000000SP2'
const ROWS = [
  { sellerPublicId: SELLER_A, companyName: 'E2E리빙샵', businessNo: '101-81-00001', ceoName: '김리빙', contactEmail: 'a@e2e.invalid', status: 'ACTIVE', productCount: 9, hasPrimaryBankAccount: true, createdAt: '2026-09-10T10:00:00' },
  { sellerPublicId: SELLER_P, companyName: 'E2E대기샵', ceoName: '박대기', status: 'PENDING', productCount: 0, hasPrimaryBankAccount: false, createdAt: '2026-09-01T09:00:00' },
]
const DETAIL_A = {
  sellerPublicId: SELLER_A, companyName: 'E2E리빙샵', businessNo: '101-81-00001', ceoName: '김리빙', contactEmail: 'a@e2e.invalid', contactPhone: '02-1000-0001',
  status: 'ACTIVE', commissionRate: 1200, createdAt: '2026-09-10T10:00:00', updatedAt: '2026-09-10T10:00:00',
  members: [{ userPublicId: 'usr_E2E0000000000000000000OW1', email: 'owner@e2e.invalid', name: '오너', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-18T00:26:27' }],
  primaryBankAccount: { id: 1, bankCode: 'KB', accountHolder: '김리빙', accountNumberSuffix: '0001', status: 'VERIFIED', verifiedAt: '2026-02-11T10:00:00' },
  bankAccounts: [
    { id: 1, bankCode: 'KB', accountHolder: '김리빙', accountNumberSuffix: '0001', status: 'VERIFIED', verifiedAt: '2026-02-11T10:00:00', isPrimary: true, referencedBySettlement: true, createdAt: '2026-02-11T10:00:00', updatedAt: '2026-02-11T10:00:00' },
    { id: 2, bankCode: 'SHINHAN', accountHolder: '김리빙', accountNumberSuffix: '5678', status: 'VERIFIED', verifiedAt: '2026-09-01T10:00:00', isPrimary: false, referencedBySettlement: false, createdAt: '2026-09-01T10:00:00', updatedAt: '2026-09-01T10:00:00' },
  ],
  productCount: 9, productCountByStatus: { SALE: 9 }, orderCount: 55, confirmedSalesAmount: 1882600,
  settlements: [{ status: 'PENDING', count: 1, netAmount: 420030 }, { status: 'PAID', count: 4, netAmount: 956970 }],
  terminable: false,
  terminationBlocks: [{ code: 'UNPAID_SETTLEMENT', count: 2 }, { code: 'ORDER_ITEM_IN_PROGRESS', count: 6 }, { code: 'CLAIM_ACTIVE', count: 1 }],
  warnings: { primaryBankAccountMissing: false, saleProductCount: 9 },
}
const MEMBER = { publicId: 'usr_E2E00000000000000000BY03', name: 'E2E회원', email: 'buyer@e2e.invalid', phone: '010-1111-2222', gradeCode: 'SILVER', createdAt: '2026-09-05T09:00:00' }

interface Captured { listQueries: URLSearchParams[]; writes: string[] }

async function mockSellerApi(page: Page): Promise<Captured> {
  const captured: Captured = { listQueries: [], writes: [] }
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/sellers/page'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const status = query.get('status')
    const items = status ? ROWS.filter((row) => row.status === status) : ROWS
    return route.fulfill({ json: { items, page: 0, size: Number(query.get('size') ?? 20), totalCount: items.length, hasNext: false } })
  })
  await page.route((url) => new RegExp(`/api/v1/admin/sellers/${SELLER_A}$`).test(url.pathname), (route) => {
    if (route.request().method() !== 'GET') {
      captured.writes.push(`${route.request().method()} ${route.request().url()}`)
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ json: DETAIL_A })
  })
  await page.route((url) => /\/api\/v1\/admin\/sellers\/[^/]+\/status$/.test(url.pathname), (route) => {
    captured.writes.push(`${route.request().method()} ${route.request().url()}`)
    return route.fulfill({ json: DETAIL_A })
  })
  // FE-41 계좌 등록·수정·전환은 호출하지 않는다 — 호출되면 writes에 기록해 단언이 실패한다.
  await page.route((url) => /\/api\/v1\/admin\/sellers\/[^/]+\/bank-accounts(\/|$)/.test(url.pathname), (route) => {
    captured.writes.push(`${route.request().method()} ${route.request().url()}`)
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/sellers') && !url.pathname.endsWith('/page'), (route) => {
    if (route.request().method() === 'POST') {
      captured.writes.push(`POST ${route.request().url()}`)
      return route.fulfill({ status: 201, json: { sellerPublicId: SELLER_A } })
    }
    return route.fulfill({ json: ROWS.map((row) => ({ sellerPublicId: row.sellerPublicId, companyName: row.companyName, status: row.status })) })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/members'), (route) => {
    const query = new URL(route.request().url()).searchParams
    const items = (query.get('keyword') ?? '').includes('E2E') ? [MEMBER] : []
    return route.fulfill({ json: { items, page: 0, size: 10, totalCount: items.length, hasNext: false } })
  })
  return captured
}

async function loginByDemo(page: Page): Promise<void> {
  await page.goto('/admin/login')
  await page.waitForLoadState('networkidle')
  const demoButton = page.getByTestId('admin-demo-login')
  test.skip((await demoButton.count()) === 0, 'NUXT_ADMIN_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
  await demoButton.click()
  await page.waitForURL(/\/admin$/)
}

test.describe('관리자 셀러 관리(FE-40)', () => {
  test('① 목록(배지·승인 대기 배너·프리셋) → 상세(차단 사유·종료 비활성 툴팁·구성원 0 경고) → 정지 다이얼로그(사유 필수) → 수정 다이얼로그(율 경고) → 입점 다이얼로그(검색·선택·폼) (PATCH·PUT·POST 0)', async ({ page }) => {
    const captured = await mockSellerApi(page)
    await loginByDemo(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/members/sellers')

    // 목록: 2행·상태 배지·승인 대기 배너(countPending = status=PENDING 조회)
    await expect(page.getByTestId('row-company')).toHaveCount(2)
    await expect(page.getByTestId('row-status').first()).toHaveText('활성')
    await expect(page.getByTestId('row-status').nth(1)).toHaveText('승인 대기')
    await expect(page.getByTestId('row-product-count').first()).toHaveAttribute('href', `/admin/products?sellerPublicId=${SELLER_A}`)
    await expect(page.getByTestId('row-bank').nth(1)).toHaveText('미등록')
    await expect(page.getByTestId('seller-pending-notice')).toContainText('승인 대기 셀러 1건')
    expect(captured.listQueries.some((query) => query.get('status') === 'PENDING' && query.get('size') === '1')).toBe(true)

    // 승인 대기 프리셋 → URL status=PENDING·1행
    await page.getByTestId('seller-pending-filter').click()
    await expect(page).toHaveURL(/status=PENDING/)
    await expect(page.getByTestId('row-company')).toHaveCount(1)
    await page.getByTestId('filter-reset').click()
    await expect(page.getByTestId('row-company')).toHaveCount(2)

    // 행 클릭 → 상세
    await page.getByTestId('row-company').first().click()
    await page.waitForURL(new RegExp(`/admin/members/sellers/${SELLER_A}`))
    await expect(page.getByTestId('seller-status')).toHaveText('활성')
    await expect(page.getByTestId('seller-rate')).toHaveText('12%')
    await expect(page.getByTestId('seller-bank-row-number').first()).toContainText('····0001')
    await expect(page.getByTestId('seller-member-withdrawn')).toContainText('탈퇴')
    await expect(page.getByTestId('seller-no-login-member')).toContainText('로그인 가능한 구성원이 없습니다')
    await expect(page.getByTestId('seller-termination-blocks')).toHaveText('종료 차단: 미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건')
    await expect(page.getByTestId('seller-order-count')).toHaveText('55건')

    // 전이 버튼: ACTIVE → 정지·종료만 노출, 종료는 비활성 + 툴팁
    await expect(page.getByTestId('action-status-SUSPENDED')).toBeEnabled()
    await expect(page.getByTestId('action-status-TERMINATED')).toBeDisabled()
    await expect(page.getByTestId('action-status-ACTIVE')).toHaveCount(0)
    await page.getByTestId('action-status-TERMINATED-wrapper').hover()
    await expect(page.getByTestId('action-terminate-blocked').first()).toContainText('종료 불가: 미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건')

    // 정지 다이얼로그: 문구·사유 비면 확인 비활성 → 취소
    await page.getByTestId('action-status-SUSPENDED').click()
    const statusDialog = page.getByTestId('admin-seller-status-dialog')
    await expect(statusDialog).toBeVisible()
    await expect(statusDialog.getByTestId('seller-status-message')).toContainText('E2E리빙샵 셀러를 정지합니다.')
    await expect(statusDialog.getByTestId('seller-status-ok')).toBeDisabled()
    await statusDialog.getByTestId('seller-status-reason').locator('textarea').first().fill('정책 위반')
    await expect(statusDialog.getByTestId('seller-status-ok')).toBeEnabled()
    await statusDialog.getByTestId('seller-status-cancel').click()
    await expect(statusDialog).toBeHidden()

    // 수정 다이얼로그: 율 변경 시 경고 강조·사유 없으면 확인 비활성 → 취소
    await page.getByTestId('action-edit').click()
    const editDialog = page.getByTestId('admin-seller-edit-dialog')
    await expect(editDialog).toBeVisible()
    await expect(editDialog.getByTestId('seller-edit-rate').locator('input')).toHaveValue('12')
    await expect(editDialog.getByTestId('seller-edit-rate-warning')).toContainText('셀러 개별 수수료율은 카테고리 수수료율보다 우선 적용됩니다')
    await editDialog.getByTestId('seller-edit-rate').locator('input').fill('5')
    await expect(editDialog.getByTestId('seller-edit-ok')).toBeDisabled()
    await editDialog.getByTestId('seller-edit-cancel').click()
    await expect(editDialog).toBeHidden()

    // 목록 복귀 → 입점 다이얼로그: 회원 검색 → 선택 → 사업자 폼 노출·상호 비면 확인 비활성 → 닫기
    await page.getByTestId('seller-back').click()
    await page.waitForURL(/\/admin\/members\/sellers(\?|$)/)
    await page.getByTestId('seller-provision-open').click()
    const provisionDialog = page.getByTestId('admin-seller-provision-dialog')
    await expect(provisionDialog).toBeVisible()
    await provisionDialog.getByTestId('seller-provision-keyword').locator('input').fill('E2E')
    await provisionDialog.getByTestId('seller-provision-search').click()
    await expect(provisionDialog.getByTestId('seller-provision-result')).toHaveCount(1)
    await provisionDialog.getByTestId('seller-provision-result').click()
    await expect(provisionDialog.getByTestId('seller-provision-owner')).toContainText('E2E회원')
    await expect(provisionDialog.getByTestId('seller-provision-company')).toBeVisible()
    await expect(provisionDialog.getByTestId('seller-provision-ok')).toBeDisabled()
    await provisionDialog.getByTestId('seller-provision-close').click()
    await expect(provisionDialog).toBeHidden()

    expect(captured.writes).toEqual([])
  })

  test('② 정산계좌 카드(FE-41): 목록 2행·끝 4자리·주 계좌 배지 → 등록 다이얼로그(검증) → 수정 다이얼로그(번호 미표시·사유 필수) → 주 계좌 전환 다이얼로그(안내·비활성) (POST·PUT·PATCH 0)', async ({ page }) => {
    const captured = await mockSellerApi(page)
    await loginByDemo(page)
    await page.goto(`/admin/members/sellers/${SELLER_A}`)
    await page.waitForLoadState('networkidle')

    // 목록: 2행·끝 4자리만·주 계좌 배지 1개·전체 번호 없음
    const rows = page.getByTestId('seller-bank-row')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0).getByTestId('seller-bank-row-number')).toHaveText('····0001')
    await expect(rows.nth(1).getByTestId('seller-bank-row-number')).toHaveText('····5678')
    await expect(rows.nth(0).getByTestId('seller-bank-row-bank')).toHaveText('KB국민은행')
    await expect(page.getByTestId('seller-bank-primary-badge')).toHaveCount(1)
    await expect(rows.nth(0).getByTestId('seller-bank-make-primary')).toBeDisabled()
    await expect(rows.nth(1).getByTestId('seller-bank-make-primary')).toBeEnabled()
    // Q6 미리보기: 정산 참조 행은 수정 비활성 + 툴팁, 미참조 행은 활성
    await expect(rows.nth(0).getByTestId('seller-bank-edit')).toBeDisabled()
    await expect(rows.nth(1).getByTestId('seller-bank-edit')).toBeEnabled()
    await rows.nth(0).getByTestId('seller-bank-edit-wrapper').hover()
    await expect(page.getByTestId('seller-bank-edit-blocked').first()).toContainText('정산 지급에 사용된 계좌입니다. 새 계좌를 등록한 뒤 주 계좌로 전환하세요.')
    await expect(page.getByTestId('seller-bank-referenced-note')).toContainText('정산 지급에 사용된 계좌는 수정할 수 없습니다')
    await expect(page.getByTestId('seller-no-bank-account')).toHaveCount(0)

    // 등록 다이얼로그: 이미 계좌가 있어 첫 계좌 안내 없음·문자 입력 검증·취소
    await page.getByTestId('seller-bank-register').click()
    const bankDialog = page.getByTestId('admin-seller-bank-dialog')
    await expect(bankDialog).toBeVisible()
    await expect(bankDialog).toContainText('정산계좌 등록')
    await expect(bankDialog.getByTestId('seller-bank-first-notice')).toHaveCount(0)
    await expect(bankDialog.getByTestId('seller-bank-ok')).toBeDisabled()
    await bankDialog.getByTestId('seller-bank-number-input').locator('input').fill('12AB-3456')
    await bankDialog.getByTestId('seller-bank-holder').locator('input').fill('김리빙')
    await bankDialog.getByTestId('seller-bank-code').click()
    await page.getByRole('option', { name: '신한은행' }).click()
    await expect(bankDialog.getByTestId('seller-bank-ok')).toBeEnabled()
    await bankDialog.getByTestId('seller-bank-ok').click()
    await expect(bankDialog).toContainText('숫자와 하이픈(-)만')
    await bankDialog.getByTestId('seller-bank-cancel').click()
    await expect(bankDialog).toBeHidden()

    // 수정 다이얼로그: 기존 번호는 표시하지 않고(끝 4자리 참고만) 사유 없으면 확인 비활성 → 취소
    await rows.nth(1).getByTestId('seller-bank-edit').click()
    await expect(bankDialog).toBeVisible()
    await expect(bankDialog).toContainText('정산계좌 수정')
    await expect(bankDialog.getByTestId('seller-bank-edit-notice')).toContainText('····5678')
    await expect(bankDialog.getByTestId('seller-bank-number-input').locator('input')).toHaveValue('')
    await bankDialog.getByTestId('seller-bank-number-input').locator('input').fill('110-000-000000')
    await expect(bankDialog.getByTestId('seller-bank-ok')).toBeDisabled()
    await bankDialog.getByTestId('seller-bank-reason').locator('textarea').first().fill('오기 정정')
    await expect(bankDialog.getByTestId('seller-bank-ok')).toBeEnabled()
    await bankDialog.getByTestId('seller-bank-cancel').click()
    await expect(bankDialog).toBeHidden()

    // 주 계좌 전환 다이얼로그: 안내 3문장·현재 주 계좌·사유 필수 → 취소
    await rows.nth(1).getByTestId('seller-bank-make-primary').click()
    const primaryDialog = page.getByTestId('admin-seller-bank-primary-dialog')
    await expect(primaryDialog).toBeVisible()
    await expect(primaryDialog.getByTestId('seller-bank-primary-headline')).toHaveText('신한은행 ····5678 (김리빙) 계좌를 주 정산계좌로 지정합니다.')
    await expect(primaryDialog.getByTestId('seller-bank-primary-current')).toContainText('KB국민은행 ····0001')
    await expect(primaryDialog.getByTestId('seller-bank-primary-notice')).toContainText('이후 정산 지급은 이 계좌로 이루어집니다.')
    await expect(primaryDialog.getByTestId('seller-bank-primary-notice')).toContainText('이미 지급완료된 정산은 지급 당시 계좌가 기록되어 있어 영향이 없습니다.')
    await expect(primaryDialog.getByTestId('seller-bank-primary-ok')).toBeDisabled()
    await primaryDialog.getByTestId('seller-bank-primary-reason').locator('textarea').first().fill('셀러 요청')
    await expect(primaryDialog.getByTestId('seller-bank-primary-ok')).toBeEnabled()
    await primaryDialog.getByTestId('seller-bank-primary-cancel').click()
    await expect(primaryDialog).toBeHidden()

    // 화면 어디에도 전체 계좌번호(예: 하이픈 포함 10자 이상 숫자열)가 없다
    await expect(page.locator('body')).not.toContainText(/\d{3}-\d{3}-\d{6}/)
    expect(captured.writes).toEqual([])
  })
})
