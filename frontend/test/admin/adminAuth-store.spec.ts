import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, type Ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'
import { useAuthStore } from '~/stores/auth'

// D-235 PR2: 관리자 인증은 BE HttpOnly 쿠키라 스토어는 토큰을 읽지 않고, 로그인 상태는 /admin/me 결과로만 판정한다.
// useCookie는 이름별 ref로 대체해 사용자 스토어의 변경 강제 상태 쿠키만 관찰한다(실 document.cookie 무관).
const { cookieRefs, useCookieMock } = vi.hoisted(() => {
  const cookieRefs = new Map<string, Ref<string | null>>()
  return {
    cookieRefs,
    useCookieMock: vi.fn((name: string, _options?: unknown) => {
      if (!cookieRefs.has(name)) cookieRefs.set(name, ref<string | null>(null))
      return cookieRefs.get(name)
    }),
  }
})

mockNuxtImport('useCookie', () => useCookieMock)

// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)

describe('adminAuth 스토어 (FE-22d 세션 분리 · D-235 쿠키 인증)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    useCookieMock.mockClear()
    fetchMock.mockReset()
    setActivePinia(createPinia())
  })

  it('JS가 읽는 토큰 쿠키를 만들지 않는다(F7)', () => {
    useAdminAuthStore()
    expect(useCookieMock).not.toHaveBeenCalled()
  })

  it('로그인 성공 → POST /v1/admin/auth/login(role 없는 본문) → 로그인 상태·role ADMIN(본문 token 미사용)', async () => {
    fetchMock.mockResolvedValue({ token: 'ignored', passwordChangeRequired: false })
    const adminAuth = useAdminAuthStore()
    await adminAuth.login('admin@zslab.local', 'pw')
    expect(fetchMock).toHaveBeenCalledWith('/v1/admin/auth/login', expect.objectContaining({ method: 'POST', body: { email: 'admin@zslab.local', password: 'pw' } }))
    expect(adminAuth.isAuthenticated).toBe(true)
    expect(adminAuth.role).toBe('ADMIN')
  })

  it('로그인 실패(401 throw) → 로그인 상태 아님', async () => {
    fetchMock.mockRejectedValue({ statusCode: 401 })
    const adminAuth = useAdminAuthStore()
    await expect(adminAuth.login('admin@zslab.local', 'wrong')).rejects.toBeDefined()
    expect(adminAuth.isAuthenticated).toBe(false)
  })

  it('데모 로그인 성공 → 서버 라우트 POST(자격증명 없는 본문) → 로그인 상태', async () => {
    fetchMock.mockResolvedValue(undefined)
    const adminAuth = useAdminAuthStore()
    await adminAuth.loginDemo()
    expect(fetchMock).toHaveBeenCalledWith('/_admin-demo/login', { method: 'POST', onRequest: expect.any(Function) })
    expect(adminAuth.isAuthenticated).toBe(true)
    expect(adminAuth.role).toBe('ADMIN')
  })

  it('ensureSession: /v1/admin/me 200 → 로그인 상태 · 이미 확인했으면 다시 묻지 않는다', async () => {
    fetchMock.mockResolvedValue({ userPublicId: 'usr_A' })
    const adminAuth = useAdminAuthStore()
    await adminAuth.ensureSession()
    await adminAuth.ensureSession()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/v1/admin/me', expect.objectContaining({ baseURL: '/api' }))
    expect(adminAuth.isAuthenticated).toBe(true)
  })

  it('ensureSession: 401 → 로그인 상태 아님(확인 완료) · 401 외 실패 → 확인 전 유지 후 다음 호출에서 재확인', async () => {
    const adminAuth = useAdminAuthStore()
    fetchMock.mockRejectedValueOnce({ statusCode: 401 })
    await adminAuth.ensureSession()
    expect(adminAuth.signedIn).toBe(false)

    setActivePinia(createPinia())
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const retried = useAdminAuthStore()
    fetchMock.mockRejectedValueOnce({ statusCode: 503 })
    await retried.ensureSession()
    expect(retried.signedIn).toBeNull()
    expect(retried.isAuthenticated).toBe(false)
    fetchMock.mockResolvedValueOnce({ userPublicId: 'usr_A' })
    await retried.ensureSession()
    expect(retried.isAuthenticated).toBe(true)
    errorSpy.mockRestore()
  })

  it('로그아웃 → POST /v1/admin/auth/logout(CSRF onRequest) 후 로그인 상태 해제 · 요청 실패여도 해제', async () => {
    const adminAuth = useAdminAuthStore()
    fetchMock.mockResolvedValueOnce(undefined)
    await adminAuth.login('admin@zslab.local', 'pw')
    fetchMock.mockResolvedValueOnce(undefined)
    await adminAuth.logout()
    expect(fetchMock).toHaveBeenLastCalledWith('/v1/admin/auth/logout', expect.objectContaining({ method: 'POST', onRequest: expect.any(Function) }))
    expect(adminAuth.isAuthenticated).toBe(false)

    fetchMock.mockResolvedValueOnce(undefined)
    await adminAuth.login('admin@zslab.local', 'pw')
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    fetchMock.mockRejectedValueOnce(new Error('network'))
    await adminAuth.logout()
    expect(adminAuth.isAuthenticated).toBe(false)
    warnSpy.mockRestore()
  })

  it('상태 격리: 관리자 상태 해제는 구매자 상태를, 구매자 상태 해제는 관리자 상태를 건드리지 않는다', async () => {
    fetchMock.mockResolvedValue({ passwordChangeRequired: false })
    const adminAuth = useAdminAuthStore()
    const userAuth = useAuthStore()
    await adminAuth.login('admin@zslab.local', 'pw')
    userAuth.signedIn = true

    adminAuth.clearSession()
    expect(adminAuth.isAuthenticated).toBe(false)
    expect(userAuth.isAuthenticated).toBe(true)

    await adminAuth.login('admin@zslab.local', 'pw')
    userAuth.clearSession()
    expect(userAuth.isAuthenticated).toBe(false)
    expect(adminAuth.isAuthenticated).toBe(true)
    // 사용자 스토어는 비밀번호 변경 강제 상태(password_change_required·Track 84) 쿠키 하나만 읽는다
    expect([...cookieRefs.keys()]).toEqual(['password_change_required'])
  })
})
