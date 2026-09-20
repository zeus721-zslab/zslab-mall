import type { Page } from '@playwright/test'

/**
 * 셀러 E2E 공용 mock(Track 90-B-3). 로그인은 loginAs(SELLER·실 BE)로 심고 셀러 조회·쓰기 API는 page.route로 mock해 로컬 DB를 바꾸지 않고
 * 결정적으로 검증한다. 각 spec은 필요한 라우트만 덮어쓴다(Playwright는 나중에 등록한 라우트를 먼저 매칭).
 */
export const SELLER_ME = {
  sellerPublicId: 'slr_E2E00000000000000000000001',
  companyName: 'E2E 셀러샵',
  status: 'ACTIVE',
  roleCode: 'SELLER_OWNER',
  pendingSettlementCount: 1,
  bankAccountRegistered: true,
}

export const SUSPENDED_PROBLEM = {
  type: 'about:blank', title: 'Forbidden', status: 403, code: 'SELLER_SUSPENDED', detail: '정지 상태의 셀러는 변경 작업을 할 수 없습니다.',
}

export async function mockSellerMe(page: Page, override: Partial<typeof SELLER_ME> = {}): Promise<void> {
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/me'), (route) => route.fulfill({ json: { ...SELLER_ME, ...override } }))
}

/** Vuetify select: 활성화 후 옵션 클릭. */
export async function pickOption(page: Page, testId: string, optionName: string): Promise<void> {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

export function pagedResponse<T>(items: T[]): { items: T[]; page: number; size: number; totalCount: number; hasNext: boolean } {
  return { items, page: 0, size: 20, totalCount: items.length, hasNext: false }
}
