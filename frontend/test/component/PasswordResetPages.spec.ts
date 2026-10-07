import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import LoginPage from '~/pages/login.vue'
import ForgotPasswordPage from '~/pages/forgot-password.vue'
import ResetPasswordPage from '~/pages/reset-password.vue'
import {
  FORGOT_PASSWORD_PAGE,
  PASSWORD_RESET_MESSAGES,
  PASSWORD_RESET_TOKEN_INVALID_CODE,
  RESET_PASSWORD_PAGE,
} from '~/lib/constants/password-reset'

/**
 * D-269 구매자 비밀번호 재설정 화면: 로그인 화면 분기(사용 가능 여부) · 요청 화면(같은 안내·오류) · 재설정 화면(토큰 없음·불일치·성공 이동·만료 토큰·기타 오류).
 * API는 usePasswordReset을 모킹한다(BE 계약은 PasswordResetIntegrationTest가 검증).
 */
const { routeMock, navigateToMock, fetchMock, resetApi } = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string>, params: {}, meta: {} },
  navigateToMock: vi.fn(),
  fetchMock: vi.fn(),
  resetApi: { fetchAvailability: vi.fn(), requestReset: vi.fn(), confirmReset: vi.fn() },
}))
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useAuthStore', () => () => ({ isAuthenticated: false, login: vi.fn(), loginDemo: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)
mockNuxtImport('usePasswordReset', () => () => resetApi)

function tokenInvalidError(): Error {
  return Object.assign(new Error('400'), { statusCode: 400, data: { code: PASSWORD_RESET_TOKEN_INVALID_CODE } })
}

beforeEach(() => {
  routeMock.query = {}
  navigateToMock.mockReset()
  fetchMock.mockReset()
  fetchMock.mockResolvedValue({ enabled: false })
  resetApi.fetchAvailability.mockReset()
  resetApi.fetchAvailability.mockResolvedValue(true)
  resetApi.requestReset.mockReset()
  resetApi.confirmReset.mockReset()
})

describe('pages/login.vue 비밀번호 찾기 분기', () => {
  it('사용 가능 true → 재설정 요청 화면 링크', async () => {
    resetApi.fetchAvailability.mockResolvedValue(true)
    const wrapper = await mountSuspended(LoginPage)
    await flushPromises()
    expect(wrapper.get('[data-testid="login-forgot-password"]').attributes('href')).toBe(FORGOT_PASSWORD_PAGE)
  })

  it('조회 실패 → 준비 중 안내 버튼 유지', async () => {
    resetApi.fetchAvailability.mockRejectedValue(new Error('network'))
    const wrapper = await mountSuspended(LoginPage)
    await flushPromises()
    await wrapper.get('[data-testid="login-forgot-password"]').trigger('click')
    expect(wrapper.get('[data-testid="login-forgot-password-notice"]').text()).toBe(PASSWORD_RESET_MESSAGES.unavailable)
  })
})

describe('pages/forgot-password.vue', () => {
  it('메일 발송 비활성(availability false) → 폼 대신 준비 중 안내', async () => {
    resetApi.fetchAvailability.mockResolvedValue(false)
    const wrapper = await mountSuspended(ForgotPasswordPage)
    await flushPromises()
    expect(wrapper.get('[data-testid="forgot-password-unavailable"]').text()).toContain(PASSWORD_RESET_MESSAGES.unavailable)
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('제출 → 요청 1회 · 폼 대신 같은 안내 문구', async () => {
    resetApi.requestReset.mockResolvedValue(undefined)
    const wrapper = await mountSuspended(ForgotPasswordPage)
    await wrapper.get('[data-testid="forgot-password-email"]').setValue('someone@zslab.test')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(resetApi.requestReset).toHaveBeenCalledExactlyOnceWith('someone@zslab.test')
    expect(wrapper.get('[data-testid="forgot-password-requested"]').text()).toContain(PASSWORD_RESET_MESSAGES.requested)
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('요청 실패 → 오류 안내 · 폼 유지', async () => {
    resetApi.requestReset.mockRejectedValue(new Error('500'))
    const wrapper = await mountSuspended(ForgotPasswordPage)
    await wrapper.get('[data-testid="forgot-password-email"]').setValue('someone@zslab.test')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="forgot-password-error"]').text()).toBe(PASSWORD_RESET_MESSAGES.requestFailed)
    expect(wrapper.find('[data-testid="forgot-password-requested"]').exists()).toBe(false)
  })
})

describe('pages/reset-password.vue', () => {
  async function mountWithToken(token: string | null) {
    routeMock.query = token === null ? {} : { token }
    return mountSuspended(ResetPasswordPage)
  }

  async function submit(wrapper: Awaited<ReturnType<typeof mountWithToken>>, password: string, confirm: string): Promise<void> {
    await wrapper.get('[data-testid="reset-password-new"]').setValue(password)
    await wrapper.get('[data-testid="reset-password-new-confirm"]').setValue(confirm)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
  }

  it('토큰 없음 → 처음부터 링크 무효 안내 · 다시 요청 링크', async () => {
    const wrapper = await mountWithToken(null)
    expect(wrapper.get('[data-testid="reset-password-link-invalid"]').text()).toContain(PASSWORD_RESET_MESSAGES.tokenMissing)
    expect(wrapper.get('[data-testid="reset-password-request-again"]').attributes('href')).toBe(FORGOT_PASSWORD_PAGE)
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('토큰을 읽은 뒤 주소창 URL에서 쿼리를 지운다', async () => {
    const replaceState = vi.spyOn(window.history, 'replaceState')
    await mountWithToken('tok')
    await flushPromises()
    expect(replaceState).toHaveBeenCalledWith(expect.anything(), '', RESET_PASSWORD_PAGE)
    replaceState.mockRestore()
  })

  it('확인 불일치 → 요청 없이 오류', async () => {
    const wrapper = await mountWithToken('tok')
    await submit(wrapper, 'new-password-1', 'new-password-2')
    expect(resetApi.confirmReset).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="reset-password-error"]').text()).toBe(PASSWORD_RESET_MESSAGES.confirmMismatch)
  })

  it('성공 → 토큰·새 비밀번호로 확정 · 로그인 화면(재설정 안내)으로 이동', async () => {
    resetApi.confirmReset.mockResolvedValue(undefined)
    const wrapper = await mountWithToken('tok')
    await submit(wrapper, 'new-password-1', 'new-password-1')
    expect(resetApi.confirmReset).toHaveBeenCalledExactlyOnceWith('tok', 'new-password-1')
    expect(navigateToMock).toHaveBeenCalledWith('/login?notice=password-reset')
  })

  it('만료·사용된 토큰(400 PASSWORD_RESET_TOKEN_INVALID) → 링크 무효 안내로 전환', async () => {
    resetApi.confirmReset.mockRejectedValue(tokenInvalidError())
    const wrapper = await mountWithToken('expired')
    await submit(wrapper, 'new-password-1', 'new-password-1')
    expect(wrapper.get('[data-testid="reset-password-link-invalid"]').text()).toContain(PASSWORD_RESET_MESSAGES.tokenInvalid)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('그 밖의 실패 → 폼 유지 · 일반 오류', async () => {
    resetApi.confirmReset.mockRejectedValue(Object.assign(new Error('500'), { statusCode: 500 }))
    const wrapper = await mountWithToken('tok')
    await submit(wrapper, 'new-password-1', 'new-password-1')
    expect(wrapper.get('[data-testid="reset-password-error"]').text()).toBe(PASSWORD_RESET_MESSAGES.confirmFailed)
    expect(wrapper.find('form').exists()).toBe(true)
  })
})
