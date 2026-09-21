import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 셀러 관리(FE-40·Track 89-D) E2E 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 목록·상세·회원 검색은 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(목록 → 승인 대기 배너·배지 → 상세(차단 사유·종료 비활성 툴팁·구성원 0 경고) → 정지 다이얼로그(사유 필수)
 * → 수정 다이얼로그(율 경고) → 입점 다이얼로그(검색·선택·폼)까지. PATCH·PUT·POST는 호출하지 않는다). ②(FE-41)는 정산계좌 카드(목록·끝 4자리·주 계좌 배지)
 * → 등록 다이얼로그(첫 계좌 안내 없음·계좌번호 검증) → 수정 다이얼로그(기존 번호 미표시·사유 필수) → 주 계좌 전환 다이얼로그(안내 3문장·주 계좌 행은 비활성)까지.
 * ③(FE-42)은 구성원 카드(역할 배지·마지막 활성 대표 비활성 툴팁·탈퇴 OWNER 제외) → 추가 다이얼로그 2경로(검색·새 계정 안내) → 역할 변경·제거 다이얼로그(사유 필수)까지.
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
  members: [{ userPublicId: 'usr_E2E0000000000000000000OW1', email: 'owner@e2e.invalid', name: '오너', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-18T00:26:27', joinedAt: '2026-09-10T10:00:00' }],
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

test.describe('관리자 셀러 관리(FE-40)', () => {
  test('① 목록(배지·승인 대기 배너·프리셋) → 상세(차단 사유·종료 비활성 툴팁·구성원 0 경고) → 정지 다이얼로그(사유 필수) → 수정 다이얼로그(율 경고) → 입점 다이얼로그(검색·선택·폼) (PATCH·PUT·POST 0)', async ({ page }) => {
    const captured = await mockSellerApi(page)
    await loginAs(page, 'ADMIN')
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
    // C-17(Track 96-1): 온보딩 체크리스트 — ACTIVE·주 계좌·판매중 9 충족, 로그인 가능 구성원만 미충족(이동 버튼 = 구성원 카드 스크롤)
    const onboarding = page.getByTestId('seller-onboarding')
    await expect(onboarding.getByTestId('seller-onboarding-active')).toHaveAttribute('data-done', 'true')
    await expect(onboarding.getByTestId('seller-onboarding-bankAccount')).toHaveAttribute('data-done', 'true')
    await expect(onboarding.getByTestId('seller-onboarding-member')).toHaveAttribute('data-done', 'false')
    await expect(onboarding.getByTestId('seller-onboarding-product')).toHaveAttribute('data-done', 'true')
    await expect(onboarding.getByTestId('seller-onboarding-member-go')).toHaveCount(1)
    await expect(onboarding.getByTestId('seller-onboarding-active-go')).toHaveCount(0)
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

    // 목록 복귀 → 입점 다이얼로그(FE-42 순서 교체): 사업자 폼이 먼저·상호/대표자만 채우면 owner 없이도 확인 활성 → 대표 계정 검색·선택은 선택 사항 → 닫기
    await page.getByTestId('seller-back').click()
    await page.waitForURL(/\/admin\/members\/sellers(\?|$)/)
    await page.getByTestId('seller-provision-open').click()
    const provisionDialog = page.getByTestId('admin-seller-provision-dialog')
    await expect(provisionDialog).toBeVisible()
    await expect(provisionDialog.getByTestId('seller-provision-company')).toBeVisible()
    await expect(provisionDialog.getByTestId('seller-provision-owner-hint')).toContainText('비워 두면 구성원 없이 등록')
    await expect(provisionDialog.getByTestId('seller-provision-ok')).toBeDisabled()
    await provisionDialog.getByTestId('seller-provision-company').locator('input').fill('E2E신규샵')
    await provisionDialog.getByTestId('seller-provision-ceo').locator('input').fill('김신규')
    await expect(provisionDialog.getByTestId('seller-provision-ok')).toBeEnabled()
    await provisionDialog.getByTestId('seller-provision-keyword').locator('input').fill('E2E')
    await provisionDialog.getByTestId('seller-provision-search').click()
    await expect(provisionDialog.getByTestId('seller-provision-result')).toHaveCount(1)
    await provisionDialog.getByTestId('seller-provision-result').click()
    await expect(provisionDialog.getByTestId('seller-provision-owner')).toContainText('E2E회원')
    await expect(provisionDialog.getByTestId('seller-provision-ok')).toBeEnabled()
    await provisionDialog.getByTestId('seller-provision-owner-change').click()
    await expect(provisionDialog.getByTestId('seller-provision-keyword')).toBeVisible()
    await provisionDialog.getByTestId('seller-provision-close').click()
    await expect(provisionDialog).toBeHidden()

    expect(captured.writes).toEqual([])
  })

  test('② 정산계좌 카드(FE-41): 목록 2행·끝 4자리·주 계좌 배지 → 등록 다이얼로그(검증) → 수정 다이얼로그(번호 미표시·사유 필수) → 주 계좌 전환 다이얼로그(안내·비활성) (POST·PUT·PATCH 0)', async ({ page }) => {
    const captured = await mockSellerApi(page)
    await loginAs(page, 'ADMIN')
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

  test('③ 구성원 카드(FE-42): 역할 배지·등록일·탈퇴 회색 → 마지막 활성 대표 제거·강등 비활성 툴팁(탈퇴 OWNER는 세지 않음) → 추가 다이얼로그 2경로(검색·새 계정 안내·역할 안내) → 역할 변경(현재 역할 제외·사유 필수) → 제거 다이얼로그(즉시 차단 안내·사유 필수) (POST·DELETE·PATCH 0)', async ({ page }) => {
    const captured = await mockSellerApi(page)
    // 활성 OWNER 1 + 탈퇴 OWNER 1 + 활성 MANAGER 1 — 마지막 활성 대표 경계. 나중에 등록한 route가 먼저 매칭된다.
    const DETAIL_M = {
      ...DETAIL_A,
      members: [
        { userPublicId: 'usr_E2E0000000000000000000OW1', email: 'owner@e2e.invalid', name: '오너', roleCode: 'SELLER_OWNER', joinedAt: '2026-09-10T10:00:00' },
        { userPublicId: 'usr_E2E0000000000000000000OW2', email: 'prev@e2e.invalid', name: '전대표', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-18T00:26:27', joinedAt: '2026-09-01T10:00:00' },
        { userPublicId: 'usr_E2E0000000000000000000MG3', email: 'mgr@e2e.invalid', name: '매니저', roleCode: 'SELLER_MANAGER', joinedAt: '2026-09-12T10:00:00' },
      ],
    }
    await page.route((url) => new RegExp(`/api/v1/admin/sellers/${SELLER_A}$`).test(url.pathname), (route) => route.fulfill({ json: DETAIL_M }))
    await page.route((url) => /\/api\/v1\/admin\/sellers\/[^/]+\/members(\/|$)/.test(url.pathname), (route) => {
      captured.writes.push(`${route.request().method()} ${route.request().url()}`)
      return route.fulfill({ status: 204 })
    })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/admin/members/sellers/${SELLER_A}`)
    await page.waitForLoadState('networkidle')

    // 목록: 3행·역할 배지·상태·등록일·로그인 가능 경고 없음(활성 2)
    const rows = page.getByTestId('seller-member-row')
    await expect(rows).toHaveCount(3)
    await expect(rows.nth(0).getByTestId('seller-member-role')).toHaveText('대표')
    await expect(rows.nth(1).getByTestId('seller-member-withdrawn')).toContainText('탈퇴')
    await expect(rows.nth(2).getByTestId('seller-member-role')).toHaveText('매니저')
    await expect(rows.nth(0).getByTestId('seller-member-joined')).toContainText('2026')
    await expect(page.getByTestId('seller-no-login-member')).toHaveCount(0)
    await expect(page.getByTestId('seller-members')).toContainText('활성 2명')

    // 마지막 활성 대표(오너): 제거·역할 변경 비활성 + 툴팁 / 탈퇴 OWNER: 제거 활성·역할 변경 비활성 / 매니저: 둘 다 활성
    await expect(rows.nth(0).getByTestId('seller-member-remove')).toBeDisabled()
    await expect(rows.nth(0).getByTestId('seller-member-change-role')).toBeDisabled()
    await rows.nth(0).getByTestId('seller-member-remove-wrapper').hover()
    await expect(page.getByTestId('seller-member-remove-blocked').first()).toContainText('마지막 활성 대표(OWNER)는 제거·강등할 수 없습니다')
    await expect(rows.nth(1).getByTestId('seller-member-remove')).toBeEnabled()
    await expect(rows.nth(1).getByTestId('seller-member-change-role')).toBeDisabled()
    await expect(rows.nth(2).getByTestId('seller-member-remove')).toBeEnabled()
    await expect(rows.nth(2).getByTestId('seller-member-change-role')).toBeEnabled()

    // 추가 다이얼로그: 경로 1 기존 회원 검색 → 선택 → 역할 안내 / 경로 2 새 계정 → 안내 3문장·필수 입력 전 확인 비활성 → 닫기
    await page.getByTestId('seller-member-add').click()
    const addDialog = page.getByTestId('admin-seller-member-add-dialog')
    await expect(addDialog).toBeVisible()
    await expect(addDialog.getByTestId('seller-member-add-ok')).toBeDisabled()
    await addDialog.getByTestId('seller-member-keyword').locator('input').fill('E2E')
    await addDialog.getByTestId('seller-member-search').click()
    await expect(addDialog.getByTestId('seller-member-result')).toHaveCount(1)
    await addDialog.getByTestId('seller-member-result').click()
    await expect(addDialog.getByTestId('seller-member-selected')).toContainText('E2E회원')
    await expect(addDialog.getByTestId('seller-member-add-ok')).toBeEnabled()
    await expect(addDialog.getByTestId('seller-member-role-notice')).toContainText('역할에 따라 제한되지 않습니다')
    await addDialog.getByTestId('seller-member-add-tab-new').click()
    await expect(addDialog.getByTestId('seller-member-new-notice')).toContainText('일반 회원(구매자) 자격도 함께 부여됩니다')
    await expect(addDialog.getByTestId('seller-member-new-notice')).toContainText('임시 비밀번호는 생성 직후 화면에 1회만 표시됩니다')
    await expect(addDialog.getByTestId('seller-member-new-notice')).toContainText('SMS 발송에 실패하면 계정은 만들어지지 않습니다')
    await expect(addDialog.getByTestId('seller-member-add-ok')).toBeDisabled()
    await addDialog.getByTestId('seller-member-new-email').locator('input').fill('new@e2e.invalid')
    await addDialog.getByTestId('seller-member-new-name').locator('input').fill('신규대표')
    await addDialog.getByTestId('seller-member-new-phone').locator('input').fill('010-9999-0000')
    await expect(addDialog.getByTestId('seller-member-add-ok')).toBeEnabled()
    await addDialog.getByTestId('seller-member-add-cancel').click()
    await expect(addDialog).toBeHidden()

    // 역할 변경 다이얼로그(매니저): 현재 역할 표시·현재 역할은 선택지에 없음·사유 없으면 확인 비활성 → 취소
    await rows.nth(2).getByTestId('seller-member-change-role').click()
    const roleDialog = page.getByTestId('admin-seller-member-role-dialog')
    await expect(roleDialog).toBeVisible()
    await expect(roleDialog.getByTestId('seller-member-role-headline')).toContainText('현재 역할: 매니저')
    await expect(roleDialog.getByTestId('seller-member-role-ok')).toBeDisabled()
    await roleDialog.getByTestId('seller-member-role-select').click()
    await expect(page.getByRole('option', { name: '매니저' })).toHaveCount(0)
    await page.getByRole('option', { name: '담당자' }).click()
    await expect(roleDialog.getByTestId('seller-member-role-ok')).toBeDisabled()
    await roleDialog.getByTestId('seller-member-role-reason').locator('textarea').first().fill('직급 조정')
    await expect(roleDialog.getByTestId('seller-member-role-ok')).toBeEnabled()
    await roleDialog.getByTestId('seller-member-role-cancel').click()
    await expect(roleDialog).toBeHidden()

    // 제거 다이얼로그(매니저): 즉시 차단·구매자 계정 유지 안내·사유 필수 → 취소 / 탈퇴 OWNER 행은 정리 문구
    await rows.nth(2).getByTestId('seller-member-remove').click()
    const removeDialog = page.getByTestId('admin-seller-member-remove-dialog')
    await expect(removeDialog).toBeVisible()
    await expect(removeDialog.getByTestId('seller-member-remove-headline')).toContainText('매니저(매니저) 구성원을 제거합니다')
    await expect(removeDialog.getByTestId('seller-member-remove-notice')).toContainText('제거 즉시 이 계정의 셀러 로그인과 셀러 기능 접근이 차단됩니다')
    await expect(removeDialog.getByTestId('seller-member-remove-notice')).toContainText('일반 회원(구매자) 계정·주문 이력은 그대로 유지')
    await expect(removeDialog.getByTestId('seller-member-remove-ok')).toBeDisabled()
    await removeDialog.getByTestId('seller-member-remove-reason').locator('textarea').first().fill('퇴사')
    await expect(removeDialog.getByTestId('seller-member-remove-ok')).toBeEnabled()
    await removeDialog.getByTestId('seller-member-remove-cancel').click()
    await expect(removeDialog).toBeHidden()
    await rows.nth(1).getByTestId('seller-member-remove').click()
    await expect(removeDialog.getByTestId('seller-member-remove-withdrawn-notice')).toContainText('탈퇴한 회원의 구성원 행을 정리합니다')
    await removeDialog.getByTestId('seller-member-remove-cancel').click()
    await expect(removeDialog).toBeHidden()

    expect(captured.writes).toEqual([])
  })

  test('④ 구성원 추가 — 새 계정 생성 201(temporaryPassword) → 결과 다이얼로그(평문·안내) → 닫기 확인 → 추가 다이얼로그 닫힘·상세 재조회·평문 없음 / 토스트에 평문 없음 (FE-55·D-204)', async ({ page }) => {
    const TEMP_PASSWORD = 'E2eMockPw2345'
    const captured = await mockSellerApi(page)
    const DETAIL_M = { ...DETAIL_A, members: [] }
    let detailReads = 0
    await page.route((url) => new RegExp(`/api/v1/admin/sellers/${SELLER_A}$`).test(url.pathname), (route) => {
      detailReads += 1
      return route.fulfill({ json: DETAIL_M })
    })
    await page.route((url) => /\/api\/v1\/admin\/sellers\/[^/]+\/members$/.test(url.pathname), (route) => {
      captured.writes.push(`${route.request().method()} ${route.request().url()}`)
      return route.fulfill({
        status: 201,
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        json: { userPublicId: 'usr_new', email: 'new@e2e.invalid', name: '신규대표', roleCode: 'SELLER_STAFF', joinedAt: '2026-09-21T10:00:00', temporaryPassword: TEMP_PASSWORD },
      })
    })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/admin/members/sellers/${SELLER_A}`)
    await page.waitForLoadState('networkidle')
    const readsBefore = detailReads

    await page.getByTestId('seller-member-add').click()
    const addDialog = page.getByTestId('admin-seller-member-add-dialog')
    await addDialog.getByTestId('seller-member-add-tab-new').click()
    await addDialog.getByTestId('seller-member-new-email').locator('input').fill('new@e2e.invalid')
    await addDialog.getByTestId('seller-member-new-name').locator('input').fill('신규대표')
    await addDialog.getByTestId('seller-member-new-phone').locator('input').fill('010-9999-0000')
    await addDialog.getByTestId('seller-member-add-ok').click()

    const result = page.getByTestId('seller-member-password-result')
    await expect(result).toBeVisible()
    await expect(result.getByTestId('seller-member-password-result-value')).toHaveText(TEMP_PASSWORD)
    await expect(result.getByTestId('seller-member-password-result-recipient')).toContainText('신규대표(new@e2e.invalid)')
    await expect(result.getByTestId('seller-member-password-result-notice')).toContainText('첫 로그인 시 비밀번호 변경이 강제됩니다')
    await expect(page.getByTestId('admin-toaster')).not.toContainText(TEMP_PASSWORD)
    expect(captured.writes).toHaveLength(1)
    expect(captured.writes[0]).toMatch(/^POST .*\/api\/v1\/admin\/sellers\/[^/]+\/members$/)
    // 결과 창이 열린 동안 추가 다이얼로그는 아직 닫히지 않는다(done은 닫기 확인 뒤)
    await expect(addDialog).toBeVisible()
    expect(detailReads).toBe(readsBefore)

    await result.getByTestId('seller-member-password-result-close').click()
    await result.getByTestId('seller-member-password-result-close-ok').click()
    await expect(result).toBeHidden()
    await expect(addDialog).toBeHidden()
    await expect.poll(() => detailReads).toBeGreaterThan(readsBefore)
    await expect(page.locator('body')).not.toContainText(TEMP_PASSWORD)
  })
})
