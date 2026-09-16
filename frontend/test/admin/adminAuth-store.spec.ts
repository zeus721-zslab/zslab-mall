import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, type Ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuthStore, ADMIN_TOKEN_COOKIE } from '#layers/admin/app/stores/adminAuth'
import { useAuthStore } from '~/stores/auth'

// useCookie를 이름별 ref로 대체해 (a) 쿠키명·옵션 (b) 로그아웃 격리 (c) role 불일치 저장 거절을 검증한다(실 document.cookie 무관).
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

const fetchMock = vi.fn()

/** 서명 검증 없는 표시용 디코드만 쓰므로 header.payload.signature 형식의 가짜 JWT로 충분하다. */
function fakeJwt(role: string): string {
  const base64url = (value: string) => Buffer.from(value).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  const exp = Math.floor(Date.now() / 1000) + 3600
  return `${base64url('{"alg":"HS256"}')}.${base64url(JSON.stringify({ role, exp }))}.sig`
}

describe('adminAuth 스토어 (FE-22d 세션 분리)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    useCookieMock.mockClear()
    fetchMock.mockReset()
    vi.stubGlobal('$fetch', fetchMock)
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('쿠키명 admin_token · path=/admin · 사용자 쿠키와 동일 속성(lax·secure·maxAge 3600)', () => {
    useAdminAuthStore()
    expect(ADMIN_TOKEN_COOKIE).toBe('admin_token')
    expect(useCookieMock).toHaveBeenCalledWith('admin_token', { path: '/admin', sameSite: 'lax', secure: true, maxAge: 3600 })
  })

  it('로그인 성공(role=ADMIN) → admin_token 저장·role ADMIN', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('ADMIN') })
    const adminAuth = useAdminAuthStore()
    await adminAuth.login('admin@zslab.local', 'pw')
    expect(fetchMock).toHaveBeenCalledWith('/v1/auth/login', expect.objectContaining({ method: 'POST', body: { email: 'admin@zslab.local', password: 'pw', role: 'ADMIN' } }))
    expect(adminAuth.isAuthenticated).toBe(true)
    expect(adminAuth.role).toBe('ADMIN')
  })

  it('응답 토큰 role≠ADMIN → 저장 거절·throw', async () => {
    fetchMock.mockResolvedValue({ token: fakeJwt('BUYER') })
    const adminAuth = useAdminAuthStore()
    await expect(adminAuth.login('buyer@zslab.local', 'pw')).rejects.toThrow()
    expect(adminAuth.token).toBeNull()
    expect(adminAuth.isAuthenticated).toBe(false)
  })

  it('로그아웃 격리: admin 로그아웃 시 auth_token 유지, 사용자 로그아웃 시 admin_token 유지', () => {
    const adminAuth = useAdminAuthStore()
    const userAuth = useAuthStore()
    adminAuth.token = fakeJwt('ADMIN')
    userAuth.token = fakeJwt('BUYER')

    adminAuth.logout()
    expect(adminAuth.token).toBeNull()
    expect(userAuth.token).not.toBeNull()

    adminAuth.token = fakeJwt('ADMIN')
    userAuth.logout()
    expect(userAuth.token).toBeNull()
    expect(adminAuth.token).not.toBeNull()
    // 두 스토어가 읽는 쿠키는 각각 admin_token·auth_token 하나뿐이다
    expect([...cookieRefs.keys()]).toEqual(['admin_token', 'auth_token'])
  })
})
