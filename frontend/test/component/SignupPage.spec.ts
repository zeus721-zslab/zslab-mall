import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SignupPage from '~/pages/signup.vue'
import SignupView from '~/skins/renew/views/SignupView.vue'
import type { SignupPageVm } from '~/skins/contracts/signup'

// 회원가입 비밀번호 확인 판정(FE-74): signupPasswordConfirm을 선언한 스킨에서만 불일치를 막는다.
// need를 선언하지 않은 스킨 경로도 검증하려고 need는 useSkinNeeds mock으로 켜고 끈다(vitest 스킨은 renew 고정·FE-75). 판정은 페이지 vm으로 직접 호출해 확인한다.
const { authMock, useSkinNeedsMock, navigateToMock, routeMock } = vi.hoisted(() => ({
  authMock: { isAuthenticated: false, signup: vi.fn() },
  useSkinNeedsMock: vi.fn(),
  navigateToMock: vi.fn(),
  routeMock: { query: {} as Record<string, string>, params: {} as Record<string, string>, meta: {} },
}))
mockNuxtImport('useAuthStore', () => () => authMock)
mockNuxtImport('useSkinNeeds', () => useSkinNeedsMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => () => routeMock)

async function mountVm(passwordConfirmRequired: boolean): Promise<SignupPageVm> {
  useSkinNeedsMock.mockImplementation((need: string) => need === 'signupPasswordConfirm' && passwordConfirmRequired)
  const wrapper = await mountSuspended(SignupPage)
  const vm = wrapper.findComponent(SignupView).props('vm') as SignupPageVm
  vm.email = 'buyer@example.com'
  vm.name = '구매자'
  vm.phone = '01012345678'
  vm.password = 'password-123'
  return vm
}

describe('pages/signup.vue 비밀번호 확인', () => {
  beforeEach(() => {
    authMock.signup.mockReset()
    authMock.signup.mockResolvedValue(undefined)
    useSkinNeedsMock.mockReset()
    navigateToMock.mockReset()
    routeMock.query = {}
  })

  it('need 켜짐 + 불일치 → 가입 요청 없이 불일치 문구', async () => {
    const vm = await mountVm(true)
    vm.passwordConfirm = 'password-999'
    await vm.handleSubmit()
    expect(authMock.signup).not.toHaveBeenCalled()
    expect(vm.errorMessage).toBe('비밀번호가 일치하지 않습니다')
  })

  it('need 켜짐 + 일치 → 가입 요청(확인 값은 보내지 않음)', async () => {
    const vm = await mountVm(true)
    vm.passwordConfirm = 'password-123'
    await vm.handleSubmit()
    expect(authMock.signup).toHaveBeenCalledWith('buyer@example.com', '구매자', '01012345678', 'password-123')
    expect(vm.errorMessage).toBe('')
  })

  it('need 꺼짐(선언 없는 스킨) →확인 값과 무관하게 가입 요청', async () => {
    const vm = await mountVm(false)
    vm.passwordConfirm = ''
    await vm.handleSubmit()
    expect(authMock.signup).toHaveBeenCalledTimes(1)
    expect(vm.errorMessage).toBe('')
  })
})
