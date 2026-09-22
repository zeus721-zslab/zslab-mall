import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminSellerMemberAddDialog from '#layers/admin/app/components/admin/AdminSellerMemberAddDialog.vue'
import type { AdminSellerDetail } from '#layers/admin/app/types/admin-seller'

/**
 * 셀러 구성원 추가 다이얼로그 — 신규 계정 임시 비밀번호 1회 표시(FE-55·D-204). 신규 계정 201(temporaryPassword) → 결과 다이얼로그가 먼저 뜨고 done은
 * 아직 없음 → 닫기 확인 후 done 1회 · 토스트 문자열에 평문 없음 · 기존 회원 연결(temporaryPassword 없음)은 즉시 done.
 */
const { sellersApiMock, membersApiMock, toastMock } = vi.hoisted(() => ({
  sellersApiMock: { addMember: vi.fn() },
  membersApiMock: { list: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminSellers', () => ({ useAdminSellers: () => sellersApiMock }))
vi.mock('#layers/admin/app/composables/useAdminMembers', () => ({ useAdminMembers: () => membersApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))

const PASSWORD = 'Abcd2345efgh'
const DETAIL: AdminSellerDetail = {
  sellerPublicId: 'slr_A',
  companyName: 'E2E리빙샵',
  ceoName: '대표',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
  members: [],
  bankAccounts: [],
  productCount: 0,
  productCountByStatus: {},
  orderCount: 0,
  confirmedSalesAmount: 0,
  settlements: [],
  terminable: true,
  terminationBlocks: [],
  warnings: { primaryBankAccountMissing: false, saleProductCount: 0 },
}

function body() {
  return document.body
}

function query<T extends HTMLElement>(testId: string): T | null {
  return body().querySelector<T>(`[data-testid="${testId}"]`)
}

async function click(testId: string): Promise<void> {
  const target = query<HTMLElement>(testId)
  if (!target) throw new Error(`${testId} 없음`)
  target.click()
  await flushPromises()
}

async function fill(testId: string, value: string): Promise<void> {
  const input = body().querySelector<HTMLInputElement>(`[data-testid="${testId}"] input`)
  if (!input) throw new Error(`${testId} input 없음`)
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function mountDialog() {
  const wrapper = await mountSuspended(AdminSellerMemberAddDialog, {
    props: { open: false, detail: DETAIL },
    global: { plugins: [createVuetify()] },
    attachTo: document.body,
  })
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

describe('AdminSellerMemberAddDialog — 신규 계정 임시 비밀번호 1회 표시(FE-55)', () => {
  beforeEach(() => {
    Object.values(sellersApiMock).forEach((fn) => fn.mockReset())
    Object.values(membersApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('신규 계정 201(temporaryPassword) → 결과 다이얼로그 표시·done 미발생·토스트에 평문 없음 → 닫기 확인 → done 1회·평문 DOM 소거', async () => {
    sellersApiMock.addMember.mockResolvedValue({ userPublicId: 'usr_N', email: 'new@e2e.invalid', name: '신규대표', roleCode: 'SELLER_OWNER', joinedAt: '2026-09-21T00:00:00', temporaryPassword: PASSWORD })
    const wrapper = await mountDialog()
    await click('seller-member-add-tab-new')
    await fill('seller-member-new-email', 'new@e2e.invalid')
    await fill('seller-member-new-name', '신규대표')
    await fill('seller-member-new-phone', '010-9999-0000')
    await click('seller-member-add-ok')

    expect(sellersApiMock.addMember).toHaveBeenCalledWith('slr_A', { newUser: { email: 'new@e2e.invalid', name: '신규대표', phone: '010-9999-0000' }, role: 'SELLER_STAFF' })
    expect(query('seller-member-password-result-value')?.textContent).toBe(PASSWORD)
    expect(query('seller-member-password-result-recipient')?.textContent).toContain('신규대표(new@e2e.invalid)')
    expect(wrapper.emitted('done')).toBeUndefined()
    expect(toastMock.success).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(toastMock.success.mock.calls)).not.toContain(PASSWORD)
    expect(JSON.stringify(toastMock.success.mock.calls)).toContain('임시 비밀번호를 확인해 전달해 주세요')

    await click('seller-member-password-result-close')
    await click('seller-member-password-result-close-ok')
    expect(wrapper.emitted('done')).toHaveLength(1)
    expect(body().innerHTML).not.toContain(PASSWORD)
    vi.unstubAllGlobals()
  })

  it.each([
    ['키 없음', {}],
    ['빈 문자열', { temporaryPassword: '' }],
  ])('신규 계정 201인데 temporaryPassword %s → fail-closed: 성공 토스트 없음·결과 다이얼로그 없음·danger 안내(재발급) → done(목록 갱신)', async (_label, extra) => {
    sellersApiMock.addMember.mockResolvedValue({ userPublicId: 'usr_N', email: 'new@e2e.invalid', name: '신규대표', roleCode: 'SELLER_STAFF', joinedAt: '2026-09-21T00:00:00', ...extra })
    const wrapper = await mountDialog()
    await click('seller-member-add-tab-new')
    await fill('seller-member-new-email', 'new@e2e.invalid')
    await fill('seller-member-new-name', '신규대표')
    await fill('seller-member-new-phone', '010-9999-0000')
    await click('seller-member-add-ok')

    expect(sellersApiMock.addMember).toHaveBeenCalledTimes(1)
    expect(toastMock.success).not.toHaveBeenCalled()
    expect(query('seller-member-password-result-value')).toBeNull()
    expect(toastMock.danger).toHaveBeenCalledTimes(1)
    expect(String(toastMock.danger.mock.calls[0]?.[0])).toContain('임시 비밀번호를 받지 못했습니다')
    expect(String(toastMock.danger.mock.calls[0]?.[0])).toContain('회원 상세에서 재발급')
    expect(wrapper.emitted('done')).toHaveLength(1)
    vi.unstubAllGlobals()
  })

  it('기존 회원 연결(temporaryPassword 없음) → 결과 다이얼로그 없이 즉시 done', async () => {
    membersApiMock.list.mockResolvedValue({ items: [{ publicId: 'usr_E', name: '기존회원', email: 'e@e2e.invalid', phone: '010-1' }], totalCount: 1, page: 0, size: 10 })
    sellersApiMock.addMember.mockResolvedValue({ userPublicId: 'usr_E', email: 'e@e2e.invalid', name: '기존회원', roleCode: 'SELLER_STAFF', joinedAt: '2026-09-21T00:00:00' })
    const wrapper = await mountDialog()
    await fill('seller-member-keyword', '기존')
    await click('seller-member-search')
    await click('seller-member-result')
    await click('seller-member-add-ok')

    expect(sellersApiMock.addMember).toHaveBeenCalledWith('slr_A', { userPublicId: 'usr_E', role: 'SELLER_STAFF' })
    expect(query('seller-member-password-result-value')).toBeNull()
    expect(wrapper.emitted('done')).toHaveLength(1)
    vi.unstubAllGlobals()
  })

  // FE-58: Vuetify VListItem은 disabled여도 click을 emit한다 — 프로그래밍 클릭(HTMLElement.click)이 핸들러 가드에서 멈추는지 검증.
  it('이미 구성원인 검색 결과 → 비활성 항목 프로그래밍 클릭 시 선택·addMember 없음', async () => {
    membersApiMock.list.mockResolvedValue({ items: [{ publicId: 'usr_E', name: '기존회원', email: 'e@e2e.invalid', phone: '010-1' }], totalCount: 1, page: 0, size: 10 })
    const wrapper = await mountSuspended(AdminSellerMemberAddDialog, {
      props: { open: false, detail: { ...DETAIL, members: [{ userPublicId: 'usr_E', email: 'e@e2e.invalid', name: '기존회원', roleCode: 'SELLER_STAFF', joinedAt: '2026-09-01T00:00:00' }] } },
      global: { plugins: [createVuetify()] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    await fill('seller-member-keyword', '기존')
    await click('seller-member-search')
    expect(query('seller-member-result')?.classList.contains('v-list-item--disabled')).toBe(true)

    await click('seller-member-result')

    expect(query('seller-member-selected')).toBeNull()
    expect(query('seller-member-result')).not.toBeNull()
    await click('seller-member-add-ok')
    expect(sellersApiMock.addMember).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeUndefined()
    vi.unstubAllGlobals()
  })
})
