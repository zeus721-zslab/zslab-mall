import { describe, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import LoginView from '~/skins/renew/views/LoginView.vue'
import type { LoginPageVm } from '~/skins/contracts/login'

/**
 * FE-81 로그인: 비밀번호 찾기는 준비 중 안내만 띄운다(이동·요청 없음) · 안내 면 데모 문구는 데모 로그인이 켜져 있을 때만.
 */
function loginVm(demoEnabled: boolean): LoginPageVm {
  return reactive<LoginPageVm>({
    passwordChangedNotice: false,
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
  it('비밀번호 찾기 클릭 → 준비 중 알림 · 페이지 이동·로그인 요청 없음', async () => {
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

  it('안내 면 데모 문구는 데모 로그인이 켜져 있을 때만 보인다', async () => {
    const enabled = await mountSuspended(LoginView, { props: { vm: loginVm(true) } })
    expect(enabled.get('[data-testid="auth-panel-note"]').text()).toBe('데모 계정으로 바로 둘러볼 수 있어요')

    const disabled = await mountSuspended(LoginView, { props: { vm: loginVm(false) } })
    expect(disabled.find('[data-testid="auth-panel-note"]').exists()).toBe(false)
  })
})
