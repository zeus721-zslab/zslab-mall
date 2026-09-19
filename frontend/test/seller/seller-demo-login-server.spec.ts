import { describe, it, expect, vi, afterEach } from 'vitest'
import { HTTP_NOT_FOUND, HTTP_UNAUTHORIZED, loginAsDemo, type BackendLoginFetcher } from '~~/server/lib/demo-login'

// 셀러 데모 서버 라우트(layers/seller/server/routes/_seller-demo)는 공용 코어(demo-login.ts)를 role 'SELLER'로 호출하고 결과를 createError로 매핑만 한다
// (admin·buyer 스펙과 동형). 여기서는 SELLER role 투과·응답 형태({ token, passwordChangeRequired } 구매자형·D-3)·자격증명 은닉을 검증한다.
const configured = { email: 'demo-seller@example.test', password: 'demo-secret' }
const API_BASE = 'http://mall-backend:8080'

describe('demo-login 서버 코어 — role SELLER', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('성공 → BE /api/v1/auth/login에 role SELLER로 대행 · 응답 { token, passwordChangeRequired } 그대로', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>().mockResolvedValue({ token: 'jwt-token', passwordChangeRequired: true })
    const result = await loginAsDemo(configured, 'SELLER', API_BASE, fetcher)
    expect(fetcher).toHaveBeenCalledWith(`${API_BASE}/api/v1/auth/login`, { email: configured.email, password: configured.password, role: 'SELLER' })
    expect(result).toEqual({ ok: true, body: { token: 'jwt-token', passwordChangeRequired: true } })
    const serialized = JSON.stringify(result)
    expect(serialized).not.toContain(configured.email)
    expect(serialized).not.toContain(configured.password)
  })

  it('미설정 → 404 · fetcher 미호출', async () => {
    const fetcher = vi.fn<BackendLoginFetcher>()
    const result = await loginAsDemo({ email: '', password: '' }, 'SELLER', API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_NOT_FOUND })
    expect(fetcher).not.toHaveBeenCalled()
  })

  it('BE 실패(계정 없음·셀러 상태 차단 401 등) → 401 일반 응답 · 로그에 자격증명 미포함', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetcher = vi.fn<BackendLoginFetcher>().mockRejectedValue(new Error('backend login responded 401'))
    const result = await loginAsDemo(configured, 'SELLER', API_BASE, fetcher)
    expect(result).toEqual({ ok: false, statusCode: HTTP_UNAUTHORIZED })
    const logged = warn.mock.calls.flat().map(String).join(' ')
    expect(logged).toContain('SELLER')
    expect(logged).not.toContain(configured.email)
    expect(logged).not.toContain(configured.password)
  })
})
