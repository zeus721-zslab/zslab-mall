import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerLoginPage from '#layers/seller/app/pages/seller/login.vue'

// 셀러 로그인 페이지(관리자 admin-login-page 동형): GET /_seller-demo/status { enabled }에 따라 데모 버튼 유무가 갈리고, 실패는 단일 문구(사유 은닉).
const { sellerAuthMock, navigateToMock, routeMock } = vi.hoisted(() => ({
  sellerAuthMock: { isAuthenticated: false, login: vi.fn(), loginDemo: vi.fn() },
  navigateToMock: vi.fn(),
  routeMock: { query: {} as Record<string, string> },
}))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('definePageMeta', () => () => {})

const fetchMock = vi.fn()

async function mountPage() {
  const wrapper = await mountSuspended(SellerLoginPage, { global: { plugins: [createVuetify()] } })
  await flushPromises()
  return wrapper
}

describe('셀러 로그인 페이지', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    sellerAuthMock.login.mockReset()
    sellerAuthMock.loginDemo.mockReset()
    navigateToMock.mockReset()
    routeMock.query = {}
    vi.stubGlobal('$fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('status enabled=true → 데모 버튼 표시 · 클릭 시 loginDemo 후 /seller 이동', async () => {
    fetchMock.mockResolvedValue({ enabled: true })
    sellerAuthMock.loginDemo.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    expect(fetchMock).toHaveBeenCalledWith('/_seller-demo/status')
    const button = wrapper.find('[data-testid="seller-demo-login"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()
    expect(sellerAuthMock.loginDemo).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/seller')
  })

  it('status enabled=false → 데모 버튼 미표시', async () => {
    fetchMock.mockResolvedValue({ enabled: false })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-demo-login"]').exists()).toBe(false)
  })

  it('status 조회 실패 → 데모 버튼 미표시(폼은 유지)', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    fetchMock.mockRejectedValue(new Error('network'))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-demo-login"]').exists()).toBe(false)
    expect(wrapper.find('#seller-email').exists()).toBe(true)
  })

  it('폼 로그인 실패(401·상태 차단 포함) → 단일 문구 표시·이동 없음', async () => {
    fetchMock.mockResolvedValue({ enabled: false })
    sellerAuthMock.login.mockRejectedValue(new Error('401'))
    const wrapper = await mountPage()
    await wrapper.find('#seller-email').setValue('seller@zslab.local')
    await wrapper.find('#seller-password').setValue('wrong')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(sellerAuthMock.login).toHaveBeenCalledWith('seller@zslab.local', 'wrong')
    expect(wrapper.find('[data-testid="seller-login-error"]').text()).toBe('이메일 또는 비밀번호를 확인하세요')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('데모 로그인 실패(401·계정 미생성) → 같은 단일 문구·이동 없음', async () => {
    fetchMock.mockResolvedValue({ enabled: true })
    sellerAuthMock.loginDemo.mockRejectedValue(new Error('401'))
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="seller-demo-login"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('이메일 또는 비밀번호를 확인하세요')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('notice=password-changed query → 변경 완료 안내 표시 · 없으면 미표시(FE-50)', async () => {
    fetchMock.mockResolvedValue({ enabled: false })
    routeMock.query = { notice: 'password-changed' }
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="seller-login-password-changed-notice"]').text()).toContain('비밀번호가 변경되었습니다')
    routeMock.query = {}
    const plain = await mountPage()
    expect(plain.find('[data-testid="seller-login-password-changed-notice"]').exists()).toBe(false)
  })
})
