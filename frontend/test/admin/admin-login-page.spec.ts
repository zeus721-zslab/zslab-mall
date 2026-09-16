import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminLoginPage from '#layers/admin/app/pages/admin/login.vue'

// 관리자 로그인 페이지 데모 버튼 표시 분기(FE-23): GET /_admin-demo/status { enabled }에 따라 버튼 유무가 갈린다. 값은 받지 않는다.
const { adminAuthMock, navigateToMock } = vi.hoisted(() => ({
  adminAuthMock: { isAuthenticated: false, login: vi.fn(), loginDemo: vi.fn() },
  navigateToMock: vi.fn(),
}))
vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => adminAuthMock }))
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('definePageMeta', () => () => {})

const fetchMock = vi.fn()

async function mountPage() {
  const wrapper = await mountSuspended(AdminLoginPage, { global: { plugins: [createVuetify()] } })
  await flushPromises()
  return wrapper
}

describe('관리자 로그인 페이지 데모 버튼', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    adminAuthMock.loginDemo.mockReset()
    navigateToMock.mockReset()
    vi.stubGlobal('$fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('status enabled=true → 데모 버튼 표시 · 클릭 시 loginDemo 후 /admin 이동', async () => {
    fetchMock.mockResolvedValue({ enabled: true })
    adminAuthMock.loginDemo.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    expect(fetchMock).toHaveBeenCalledWith('/_admin-demo/status')
    const button = wrapper.find('[data-testid="admin-demo-login"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()
    expect(adminAuthMock.loginDemo).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/admin')
  })

  it('status enabled=false → 데모 버튼 미표시', async () => {
    fetchMock.mockResolvedValue({ enabled: false })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="admin-demo-login"]').exists()).toBe(false)
  })

  it('status 조회 실패 → 데모 버튼 미표시(폼은 유지)', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    fetchMock.mockRejectedValue(new Error('network'))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="admin-demo-login"]').exists()).toBe(false)
    expect(wrapper.find('#admin-email').exists()).toBe(true)
  })

  it('데모 로그인 실패(401) → 일반 오류 문구 표시·이동 없음', async () => {
    fetchMock.mockResolvedValue({ enabled: true })
    adminAuthMock.loginDemo.mockRejectedValue(new Error('401'))
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="admin-demo-login"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('이메일 또는 비밀번호를 확인하세요')
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
