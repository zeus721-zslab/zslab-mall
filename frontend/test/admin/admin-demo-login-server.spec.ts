import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  HTTP_NOT_FOUND,
  HTTP_UNAUTHORIZED,
  isAdminDemoConfigured,
  loginAsAdminDemo,
  type BackendLoginFetcher,
} from '#layers/admin/server/lib/admin-demo-login'

// 서버 라우트 코어(FE-23). 라우트 핸들러(status.get/login.post)는 이 결과를 createError로 매핑만 하므로 코어를 검증한다.
// 자격증명 값은 fetcher 인자로만 흘러야 하며 반환값·에러 어디에도 실리지 않는다.
const configured = { adminDemoEmail: 'demo-admin@example.test', adminDemoPassword: 'demo-secret' }
const API_BASE = 'http://mall-backend:8080'

describe('admin-demo-login 서버 코어', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('미설정(둘 중 하나라도 blank) → enabled false · 404', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>()
    expect(isAdminDemoConfigured({ adminDemoEmail: '', adminDemoPassword: '' })).toBe(false)
    expect(isAdminDemoConfigured({ adminDemoEmail: 'a@b.c', adminDemoPassword: ' ' })).toBe(false)
    const result = await loginAsAdminDemo({ adminDemoEmail: 'a@b.c', adminDemoPassword: '' }, API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_NOT_FOUND })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('성공 → BE /api/v1/auth/login에 role ADMIN으로 대행 · 응답은 { token }만', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>().mockResolvedValue({ token: 'jwt-token' })
    expect(isAdminDemoConfigured(configured)).toBe(true)
    const result = await loginAsAdminDemo(configured, API_BASE, fetcher)
    expect(fetcher).toHaveBeenCalledWith(`${API_BASE}/api/v1/auth/login`, {
      email: configured.adminDemoEmail,
      password: configured.adminDemoPassword,
      role: 'ADMIN',
    })
    expect(result).toEqual({ ok: true, body: { token: 'jwt-token' } })
    const serialized = JSON.stringify(result)
    expect(serialized).not.toContain(configured.adminDemoEmail)
    expect(serialized).not.toContain(configured.adminDemoPassword)
  })

  it('BE 실패(401 등 throw) → 401 일반 응답 · 에러 본문에 자격증명 미포함', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetcher = vi.fn<BackendLoginFetcher>().mockRejectedValue(new Error('Invalid email or password.'))
    const result = await loginAsAdminDemo(configured, API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_UNAUTHORIZED })
    const logged = warn.mock.calls.flat().map(String).join(' ')
    expect(logged).not.toContain(configured.adminDemoEmail)
    expect(logged).not.toContain(configured.adminDemoPassword)
  })
})
