import { describe, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import LoginView from '~/skins/renew/views/LoginView.vue'
import type { LoginPageVm } from '~/skins/contracts/login'
import { FORGOT_PASSWORD_PAGE, PASSWORD_RESET_MESSAGES } from '~/lib/constants/password-reset'

/**
 * FE-81 로그인: 비밀번호 찾기는 실 메일 발송이 꺼져 있으면 준비 중 안내만 띄운다(이동·요청 없음) · 켜져 있으면 재설정 요청 화면 링크(D-269) ·
 * 안내 면 데모 문구는 데모 로그인이 켜져 있을 때만.
 */
function loginVm(demoEnabled: boolean, passwordResetEnabled = false): LoginPageVm {
  return reactive<LoginPageVm>({
    passwordChangedNotice: false,
    passwordResetNotice: false,
    passwordResetEnabled,
    email: '',
    password: '',
    submitting: false,
    errorMessage: '',
    demoEnabled,
    handleSubmit: vi.fn(async () => {}),
    handleDemoLogin: vi.fn(async () => {}),
    signupLink: '/signup',
  })
}

describe('renew LoginView', () => {
  it('재설정 비활성: 비밀번호 찾기 클릭 → 준비 중 알림 · 페이지 이동·로그인 요청 없음', async () => {
    const vm = loginVm(false)
    const wrapper = await mountSuspended(LoginView, { props: { vm } })
    const router = useRouter()
    const pathBefore = router.currentRoute.value.fullPath
    expect(wrapper.find('[data-testid="login-forgot-password-notice"]').exists()).toBe(false)

    await wrapper.get('[data-testid="login-forgot-password"]').trigger('click')

    const notice = wrapper.get('[data-testid="login-forgot-password-notice"]')
    expect(notice.text()).toBe('비밀번호 찾기는 준비 중입니다.')
    expect(notice.attributes('data-tone')).toBe('info')
    expect(router.currentRoute.value.fullPath).toBe(pathBefore)
    expect(vm.handleSubmit).not.toHaveBeenCalled()
    expect(vm.handleDemoLogin).not.toHaveBeenCalled()
  })

  it('재설정 활성(D-269): 비밀번호 찾기는 재설정 요청 화면 링크 · 준비 중 안내 없음', async () => {
    const wrapper = await mountSuspended(LoginView, { props: { vm: loginVm(false, true) } })
    const link = wrapper.get('[data-testid="login-forgot-password"]')
    expect(link.element.tagName).toBe('A')
    expect(link.attributes('href')).toBe(FORGOT_PASSWORD_PAGE)
    expect(wrapper.find('[data-testid="login-forgot-password-notice"]').exists()).toBe(false)
  })

  it('재설정 완료 안내(D-269): passwordResetNotice면 성공 알림', async () => {
    const vm = loginVm(false, true)
    vm.passwordResetNotice = true
    const wrapper = await mountSuspended(LoginView, { props: { vm } })
    const notice = wrapper.get('[data-testid="login-password-reset-notice"]')
    expect(notice.text()).toBe(PASSWORD_RESET_MESSAGES.completed)
    expect(notice.attributes('data-tone')).toBe('success')
  })

  it('안내 면 데모 문구는 데모 로그인이 켜져 있을 때만 보인다', async () => {
    const enabled = await mountSuspended(LoginView, { props: { vm: loginVm(true) } })
    expect(enabled.get('[data-testid="auth-panel-note"]').text()).toBe('데모 계정으로 바로 둘러볼 수 있어요')

    const disabled = await mountSuspended(LoginView, { props: { vm: loginVm(false) } })
    expect(disabled.find('[data-testid="auth-panel-note"]').exists()).toBe(false)
  })
})
