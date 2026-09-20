import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerPasswordPage from '#layers/seller/app/pages/seller/settings/password.vue'
import { PASSWORD_CHANGE_FORM_MESSAGES, validatePasswordChangeForm } from '#layers/seller/app/lib/seller-password-form'

/**
 * 셀러 비밀번호 변경(Track 90-D-2·FE-50). 클라이언트 검증(길이 8~72·확인 일치·현재 비번 필수)은 순수 함수로, 페이지는 제출 흐름만 본다:
 * 검증 실패 → API 미호출 · 중복 제출 차단 · 204 → logout + /seller/login?notice=password-changed · 400 MALFORMED_REQUEST → 현재 비밀번호 필드 ·
 * 400 VALIDATION_FAILED → fieldErrors 필드 · 그 외 → danger 토스트. API·스토어·토스트는 mock(실 네트워크 없음).
 */
const { apiMock, sellerAuthMock, toastMock, navigateToMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  sellerAuthMock: { passwordChangeRequired: false, logout: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
  navigateToMock: vi.fn(),
}))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))
mockNuxtImport('useSellerApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('definePageMeta', () => () => {})

const VALID = { currentPassword: 'current-pw-1', newPassword: 'new-password-1', newPasswordConfirm: 'new-password-1' }

describe('validatePasswordChangeForm', () => {
  it('유효 입력 → 오류 없음', () => {
    expect(validatePasswordChangeForm(VALID)).toEqual({})
  })

  it('현재 비밀번호 빈 값 · 새 비밀번호 7자/73자 · 확인 불일치 → 필드별 오류', () => {
    expect(validatePasswordChangeForm({ ...VALID, currentPassword: '' })).toEqual({ currentPassword: PASSWORD_CHANGE_FORM_MESSAGES.currentPasswordRequired })
    expect(validatePasswordChangeForm({ ...VALID, newPassword: 'a'.repeat(7), newPasswordConfirm: 'a'.repeat(7) })).toEqual({ newPassword: PASSWORD_CHANGE_FORM_MESSAGES.newPasswordLength })
    expect(validatePasswordChangeForm({ ...VALID, newPassword: 'a'.repeat(73), newPasswordConfirm: 'a'.repeat(73) })).toEqual({ newPassword: PASSWORD_CHANGE_FORM_MESSAGES.newPasswordLength })
    expect(validatePasswordChangeForm({ ...VALID, newPasswordConfirm: 'other-password' })).toEqual({ newPasswordConfirm: PASSWORD_CHANGE_FORM_MESSAGES.newPasswordConfirmMismatch })
  })

  it('경계 8자·72자 통과 · 공백은 trim하지 않는다(8자 공백 포함 통과)', () => {
    expect(validatePasswordChangeForm({ ...VALID, newPassword: 'a'.repeat(8), newPasswordConfirm: 'a'.repeat(8) })).toEqual({})
    expect(validatePasswordChangeForm({ ...VALID, newPassword: 'a'.repeat(72), newPasswordConfirm: 'a'.repeat(72) })).toEqual({})
    expect(validatePasswordChangeForm({ ...VALID, newPassword: ' abcdef ', newPasswordConfirm: ' abcdef ' })).toEqual({})
  })
})

async function mountPage() {
  const wrapper = await mountSuspended(SellerPasswordPage, { global: { plugins: [createVuetify()] } })
  await flushPromises()
  return wrapper
}

async function fillAndSubmit(wrapper: Awaited<ReturnType<typeof mountPage>>, input: typeof VALID): Promise<void> {
  await wrapper.find('#seller-current-password').setValue(input.currentPassword)
  await wrapper.find('#seller-new-password').setValue(input.newPassword)
  await wrapper.find('#seller-new-password-confirm').setValue(input.newPasswordConfirm)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('셀러 비밀번호 변경 페이지', () => {
  beforeEach(() => {
    apiMock.mockReset()
    sellerAuthMock.logout.mockReset()
    sellerAuthMock.passwordChangeRequired = false
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    navigateToMock.mockReset()
  })

  it('사전 안내(모든 기기·구매자 로그인 로그아웃) 표시 · 임시 비밀번호 세션이 아니면 임시 안내 없음', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-password-logout-notice"]').text()).toContain('구매자 로그인이 함께 로그아웃')
    expect(wrapper.find('[data-testid="seller-password-temporary-notice"]').exists()).toBe(false)
  })

  it('임시 비밀번호 세션(passwordChangeRequired) → 임시 안내 표시', async () => {
    sellerAuthMock.passwordChangeRequired = true
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-password-temporary-notice"]').text()).toContain('임시 비밀번호로 로그인했습니다')
  })

  it('확인 불일치 → API 미호출·확인 필드 오류 표시', async () => {
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, { ...VALID, newPasswordConfirm: 'mismatch-1' })
    expect(apiMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(PASSWORD_CHANGE_FORM_MESSAGES.newPasswordConfirmMismatch)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('204 → PATCH /v1/users/me/password(확인 필드 제외) → logout → /seller/login?notice=password-changed', async () => {
    apiMock.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(apiMock).toHaveBeenCalledWith('/v1/users/me/password', {
      method: 'PATCH',
      body: { currentPassword: VALID.currentPassword, newPassword: VALID.newPassword },
    })
    expect(sellerAuthMock.logout).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/seller/login?notice=password-changed')
    expect(toastMock.danger).not.toHaveBeenCalled()
  })

  it('제출 중 재제출 → API 1회만(중복 클릭 차단)', async () => {
    let resolveFirst: () => void = () => {}
    apiMock.mockImplementationOnce(() => new Promise<void>((resolve) => { resolveFirst = resolve }))
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledTimes(1)
    resolveFirst()
    await flushPromises()
    expect(sellerAuthMock.logout).toHaveBeenCalledTimes(1)
  })

  it('400 MALFORMED_REQUEST(현재 비밀번호 불일치) → 현재 비밀번호 필드 오류·logout 없음·토스트 없음', async () => {
    apiMock.mockRejectedValue({ status: 400, data: { code: 'MALFORMED_REQUEST', detail: '현재 비밀번호가 일치하지 않습니다.' } })
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(wrapper.text()).toContain(PASSWORD_CHANGE_FORM_MESSAGES.currentPasswordMismatch)
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(toastMock.danger).not.toHaveBeenCalled()
  })

  it('400 VALIDATION_FAILED(fieldErrors) → 해당 필드 오류 표시', async () => {
    apiMock.mockRejectedValue({
      status: 400,
      data: { code: 'VALIDATION_FAILED', detail: 'newPassword: 비밀번호는 8자 이상 72자 이하여야 합니다.', fieldErrors: [{ field: 'newPassword', message: '비밀번호는 8자 이상 72자 이하여야 합니다.' }] },
    })
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(wrapper.text()).toContain('비밀번호는 8자 이상 72자 이하여야 합니다.')
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
    expect(toastMock.danger).not.toHaveBeenCalled()
  })

  it('그 외 오류(500) → danger 토스트·필드 오류 없음·logout 없음', async () => {
    apiMock.mockRejectedValue({ status: 500, data: { code: 'INTERNAL_ERROR' } })
    const wrapper = await mountPage()
    await fillAndSubmit(wrapper, VALID)
    expect(toastMock.danger).toHaveBeenCalledWith('서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
    expect(wrapper.text()).not.toContain(PASSWORD_CHANGE_FORM_MESSAGES.currentPasswordMismatch)
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
  })
})
