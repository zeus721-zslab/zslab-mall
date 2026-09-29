import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  HTTP_NOT_FOUND,
  HTTP_UNAUTHORIZED,
  isDemoConfigured,
  loginAsDemo,
  type BackendLoginFetcher,
} from '~~/server/lib/demo-login'

// 서버 라우트 코어(FE-23·FE-43 공용). 라우트 핸들러(_admin-demo·_demo의 status.get/login.post)는 이 결과를 createError로 매핑만 하므로 코어를 검증한다.
// 자격증명 값은 fetcher 인자로만 흘러야 하며 반환값·에러 어디에도 실리지 않는다.
const configured = { email: 'demo-admin@example.test', password: 'demo-secret' }
const API_BASE = 'http://mall-backend:8080'

describe('demo-login 서버 코어', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('미설정(둘 중 하나라도 blank) → enabled false · 404', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>()
    expect(isDemoConfigured({ email: '', password: '' })).toBe(false)
    expect(isDemoConfigured({ email: 'a@b.c', password: ' ' })).toBe(false)
    const result = await loginAsDemo({ email: 'a@b.c', password: '' }, 'ADMIN', API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_NOT_FOUND })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('성공 → BE 관리자 로그인 경로(/api/v1/admin/auth/login)로 대행 · 본문 { passwordChangeRequired }만 · BE Set-Cookie 원문 전달', async () => {
    const setCookies = ['__Secure-admin_at=cookie-value; Path=/api/v1/admin; HttpOnly', 'XSRF-TOKEN=xsrf-value; Path=/']
    const fetcher = vi.fn<BackendLoginFetcher>().mockResolvedValue({ passwordChangeRequired: false, setCookies })
    expect(isDemoConfigured(configured)).toBe(true)
    const result = await loginAsDemo(configured, 'ADMIN', API_BASE, fetcher)
    expect(fetcher).toHaveBeenCalledWith(`${API_BASE}/api/v1/admin/auth/login`, {
      email: configured.email,
      password: configured.password,
    })
    expect(result).toEqual({ ok: true, body: { passwordChangeRequired: false }, setCookies })
    const serialized = JSON.stringify(result)
    expect(serialized).not.toContain(configured.email)
    expect(serialized).not.toContain(configured.password)
  })

  it('role BUYER 인자 → BE 구매자 로그인 경로(/api/v1/auth/buyer/login) · BE passwordChangeRequired true 투과', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>().mockResolvedValue({ passwordChangeRequired: true, setCookies: [] })
    const result = await loginAsDemo(configured, 'BUYER', API_BASE, fetcher)
    expect(fetcher).toHaveBeenCalledWith(`${API_BASE}/api/v1/auth/buyer/login`, { email: configured.email, password: configured.password })
    expect(result).toEqual({ ok: true, body: { passwordChangeRequired: true }, setCookies: [] })
  })

  it('BE 실패(401 등 throw) → 401 일반 응답 · 에러 본문에 자격증명 미포함', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetcher = vi.fn<BackendLoginFetcher>().mockRejectedValue(new Error('Invalid email or password.'))
    const result = await loginAsDemo(configured, 'ADMIN', API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_UNAUTHORIZED })
    const logged = warn.mock.calls.flat().map(String).join(' ')
    expect(logged).not.toContain(configured.email)
    expect(logged).not.toContain(configured.password)
  })
})
