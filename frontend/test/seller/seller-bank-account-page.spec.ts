import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerBankAccountPage from '#layers/seller/app/pages/seller/settings/bank-account.vue'
import {
  SELLER_BANK_ACCOUNT_MESSAGES,
  formatSellerBankAccount,
  validateSellerBankAccountForm,
} from '#layers/seller/app/lib/seller-bank-account'
import type { SellerBankAccount, SellerBankAccountStatus } from '#layers/seller/app/types/seller-bank-account'
import type { SellerMe } from '#layers/seller/app/types/seller-me'

/**
 * 셀러 정산계좌(Track 90-D-3·FE-51). 클라이언트 검증(은행 필수·계좌번호 숫자/하이픈 6~30·예금주 1~50)과 마스킹 표기는 순수 함수로, 페이지는
 * 역할 분기(OWNER 폼 / 그 외 조회 안내)·목록 상태(로딩·빈·에러·행)·제출 흐름(검증 실패 미호출·중복 차단·201 → 토스트+재조회·400 fieldErrors·
 * 403 SELLER_OWNER_REQUIRED 토스트)만 본다. API·me·토스트는 mock(실 네트워크 없음).
 */
const { apiMock, meState, toastMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  meState: { value: null as SellerMe | null },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/seller/app/composables/useSellerMe', () => ({ useSellerMe: () => ({ me: meState, error: { value: false }, load: vi.fn(), clear: vi.fn() }) }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))
mockNuxtImport('useSellerApi', () => () => apiMock)
mockNuxtImport('definePageMeta', () => () => {})

const VALID = { bankCode: 'KB', accountNumber: '110-123-456789', accountHolder: '대표자' }

function me(roleCode: SellerMe['roleCode']): SellerMe {
  return { sellerPublicId: 'slr_x', companyName: '테스트셀러', status: 'ACTIVE', roleCode, pendingSettlementCount: 0, bankAccountRegistered: false }
}

function account(id: number, isPrimary: boolean, status: SellerBankAccountStatus = 'VERIFIED'): SellerBankAccount {
  return { id, bankCode: 'KB', accountNumberSuffix: '6789', accountHolder: '대표자', isPrimary, status, createdAt: '2026-09-21T10:00:00' }
}

describe('validateSellerBankAccountForm · formatSellerBankAccount', () => {
  it('유효 입력 → 오류 없음 · 경계 6자·30자 통과 · 앞뒤 공백은 trim', () => {
    expect(validateSellerBankAccountForm(VALID)).toEqual({})
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: '123456' })).toEqual({})
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: '1'.repeat(30) })).toEqual({})
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: ' 110-123-456789 ', accountHolder: ' 대표자 ' })).toEqual({})
  })

  it('은행 미선택 · 계좌번호 문자/5자/31자 · 예금주 빈 값/51자 → 필드별 오류', () => {
    expect(validateSellerBankAccountForm({ ...VALID, bankCode: '' })).toEqual({ bankCode: SELLER_BANK_ACCOUNT_MESSAGES.bankCodeRequired })
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: '110-ABC-456' })).toEqual({ accountNumber: SELLER_BANK_ACCOUNT_MESSAGES.accountNumberPattern })
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: '12345' })).toEqual({ accountNumber: SELLER_BANK_ACCOUNT_MESSAGES.accountNumberLength })
    expect(validateSellerBankAccountForm({ ...VALID, accountNumber: '1'.repeat(31) })).toEqual({ accountNumber: SELLER_BANK_ACCOUNT_MESSAGES.accountNumberLength })
    expect(validateSellerBankAccountForm({ ...VALID, accountHolder: '  ' })).toEqual({ accountHolder: SELLER_BANK_ACCOUNT_MESSAGES.accountHolderRequired })
    expect(validateSellerBankAccountForm({ ...VALID, accountHolder: '가'.repeat(51) })).toEqual({ accountHolder: SELLER_BANK_ACCOUNT_MESSAGES.accountHolderLength })
  })

  it('표기: 은행 표시명 + ···끝4자리 · 목록 밖 코드는 코드 그대로', () => {
    expect(formatSellerBankAccount({ bankCode: 'KB', accountNumberSuffix: '6789' })).toBe('KB국민은행 ···6789')
    expect(formatSellerBankAccount({ bankCode: '004', accountNumberSuffix: '0123' })).toBe('004 ···0123')
  })
})

async function mountPage() {
  const wrapper = await mountSuspended(SellerBankAccountPage, { global: { plugins: [createVuetify()] } })
  await flushPromises()
  return wrapper
}

async function fillAndSubmit(wrapper: Awaited<ReturnType<typeof mountPage>>, input: typeof VALID): Promise<void> {
  // v-select는 네이티브 input이 없어 컴포넌트 vm으로 값을 넣는다(관리자 다이얼로그 spec 관례)
  const select = wrapper.findComponent({ name: 'VSelect' })
  await select.setValue(input.bankCode)
  await wrapper.find('#seller-account-number').setValue(input.accountNumber)
  await wrapper.find('#seller-account-holder').setValue(input.accountHolder)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

function registerCalls() {
  return apiMock.mock.calls.filter((call) => (call[1] as { method?: string } | undefined)?.method === 'POST')
}

describe('셀러 정산계좌 페이지', () => {
  beforeEach(() => {
    apiMock.mockReset()
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    meState.value = me('SELLER_OWNER')
  })

  it('OWNER → 등록 폼·안내 1줄 표시 · 조회 전용 안내 없음 · 진입 시 GET 목록 1회', async () => {
    apiMock.mockResolvedValue([])
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-bank-account-form-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="seller-bank-account-form-notice"]').text()).toContain('주 정산계좌로 지정')
    expect(wrapper.find('[data-testid="seller-bank-account-readonly-notice"]').exists()).toBe(false)
    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(apiMock).toHaveBeenCalledWith('/v1/seller/bank-accounts')
  })

  it.each(['SELLER_MANAGER', 'SELLER_STAFF'] as const)('%s → 등록 폼 없음·조회 전용 안내', async (roleCode) => {
    meState.value = me(roleCode)
    apiMock.mockResolvedValue([account(1, true)])
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-bank-account-form-card"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="seller-bank-account-readonly-notice"]').text()).toContain(SELLER_BANK_ACCOUNT_MESSAGES.readOnlyNotice)
    expect(wrapper.find('[data-testid="seller-bank-account-rows"]').exists()).toBe(true)
  })

  it('목록 0건 → 빈 상태 · 2건 → 행 2·주 계좌 칩 1·마스킹 표기·전체 계좌번호 없음', async () => {
    apiMock.mockResolvedValue([])
    const empty = await mountPage()
    expect(empty.find('[data-testid="seller-bank-account-empty"]').text()).toContain(SELLER_BANK_ACCOUNT_MESSAGES.emptyTitle)

    apiMock.mockResolvedValue([account(1, true), { ...account(2, false, 'PENDING'), bankCode: 'SHINHAN', accountNumberSuffix: '4321' }])
    const filled = await mountPage()
    expect(filled.find('[data-testid="seller-bank-account-row-1"]').exists()).toBe(true)
    expect(filled.find('[data-testid="seller-bank-account-row-2"]').exists()).toBe(true)
    expect(filled.findAll('[data-testid="seller-bank-account-primary"]')).toHaveLength(1)
    expect(filled.find('[data-testid="seller-bank-account-row-1"]').text()).toContain('KB국민은행 ···6789')
    expect(filled.find('[data-testid="seller-bank-account-row-2"]').text()).toContain('신한은행 ···4321').toContain('확인 대기')
    expect(filled.text()).not.toContain('110-123-456789')
  })

  it('목록 실패 → 에러 알림·다시 시도 → GET 재호출', async () => {
    apiMock.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR' } }).mockResolvedValue([])
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-bank-account-error"]').exists()).toBe(true)
    await wrapper.find('[data-testid="seller-bank-account-retry"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="seller-bank-account-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="seller-bank-account-empty"]').exists()).toBe(true)
  })

  it('검증 실패(계좌번호 문자) → POST 미호출·필드 오류 표시', async () => {
    apiMock.mockResolvedValue([])
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, { ...VALID, accountNumber: '110-ABC' })
    expect(registerCalls()).toHaveLength(0)
    expect(wrapper.text()).toContain(SELLER_BANK_ACCOUNT_MESSAGES.accountNumberPattern)
  })

  it('201 → POST /v1/seller/bank-accounts(trim 본문) → success 토스트 → 목록 재조회·폼 초기화', async () => {
    apiMock.mockImplementation((_path: string, options?: { method?: string }) =>
      options?.method === 'POST' ? Promise.resolve(account(1, true)) : Promise.resolve([]))
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, { ...VALID, accountNumber: ' 110-123-456789 ', accountHolder: ' 대표자 ' })
    expect(registerCalls()).toHaveLength(1)
    expect(registerCalls()[0]?.[1]).toEqual({ method: 'POST', body: VALID })
    expect(toastMock.success).toHaveBeenCalledWith(SELLER_BANK_ACCOUNT_MESSAGES.registered)
    expect(apiMock.mock.calls.filter((call) => call[1] === undefined)).toHaveLength(2)
    expect((wrapper.find('#seller-account-number').element as HTMLInputElement).value).toBe('')
    expect(toastMock.danger).not.toHaveBeenCalled()
  })

  it('제출 중 재제출 → POST 1회만(중복 클릭 차단)', async () => {
    let resolveFirst: (value: SellerBankAccount) => void = () => {}
    apiMock.mockImplementation((_path: string, options?: { method?: string }) =>
      options?.method === 'POST' ? new Promise<SellerBankAccount>((resolve) => { resolveFirst = resolve }) : Promise.resolve([]))
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(registerCalls()).toHaveLength(1)
    resolveFirst(account(1, true))
    await flushPromises()
    expect(toastMock.success).toHaveBeenCalledTimes(1)
  })

  it('400 VALIDATION_FAILED(fieldErrors) → 해당 필드 오류·토스트 없음', async () => {
    apiMock.mockImplementation((_path: string, options?: { method?: string }) =>
      options?.method === 'POST'
        ? Promise.reject({ status: 400, data: { code: 'VALIDATION_FAILED', detail: 'accountNumber: 계좌번호는 숫자와 하이픈만 허용합니다.', fieldErrors: [{ field: 'accountNumber', message: '계좌번호는 숫자와 하이픈만 허용합니다.' }] } })
        : Promise.resolve([]))
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(wrapper.text()).toContain('계좌번호는 숫자와 하이픈만 허용합니다.')
    expect(toastMock.danger).not.toHaveBeenCalled()
  })

  it('403 SELLER_OWNER_REQUIRED → danger 토스트(대표만 가능) · 403 SELLER_SUSPENDED → danger 토스트(정지 문구)', async () => {
    apiMock.mockImplementation((_path: string, options?: { method?: string }) =>
      options?.method === 'POST' ? Promise.reject({ status: 403, data: { code: 'SELLER_OWNER_REQUIRED' } }) : Promise.resolve([]))
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(toastMock.danger).toHaveBeenCalledWith(expect.stringContaining('셀러 대표(OWNER)만'))

    toastMock.danger.mockReset()
    apiMock.mockImplementation((_path: string, options?: { method?: string }) =>
      options?.method === 'POST' ? Promise.reject({ status: 403, data: { code: 'SELLER_SUSPENDED' } }) : Promise.resolve([]))
    const suspended = await mountPage()
    await fillAndSubmit(suspended, VALID)
    expect(toastMock.danger).toHaveBeenCalledWith(expect.stringContaining('정지 상태'))
  })
})
