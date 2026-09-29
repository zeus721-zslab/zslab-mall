import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { applyCsrfHeader, CSRF_TOKEN_PATH } from '~/lib/csrf'

// 공통 CSRF 함수(D-235 PR3 K7). XSRF-TOKEN 쿠키가 없을 때만 인증 전 토큰 발급(GET /api/v1/auth/csrf)을 1회 호출하고, 동시 요청은 그 호출을 공유한다.
// 발급 응답의 Set-Cookie는 브라우저가 저장하므로 여기서는 $fetch mock이 document.cookie를 채우는 것으로 대신한다(실 네트워크 없음).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn<(path: string, options: { baseURL: string }) => Promise<void>>() }))
mockNuxtImport('$fetch', () => fetchMock)

function setXsrfCookie(value: string): void {
  document.cookie = `XSRF-TOKEN=${value}; path=/`
}

describe('applyCsrfHeader', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    document.cookie = 'XSRF-TOKEN=; path=/; max-age=0'
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('XSRF-TOKEN 쿠키가 있으면 발급 호출 없이 원문 값을 X-XSRF-TOKEN으로', async () => {
    setXsrfCookie('existing')
    const headers = new Headers()
    await applyCsrfHeader({ method: 'POST', headers })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(headers.get('X-XSRF-TOKEN')).toBe('existing')
  })

  it('쿠키가 없으면 GET /v1/auth/csrf를 먼저 호출하고 발급된 값을 싣는다', async () => {
    fetchMock.mockImplementation(async () => setXsrfCookie('issued'))
    const headers = new Headers()
    await applyCsrfHeader({ method: 'POST', headers })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]?.[0]).toBe(CSRF_TOKEN_PATH)
    expect(headers.get('X-XSRF-TOKEN')).toBe('issued')
  })

  it('동시 unsafe 요청은 진행 중인 발급 호출 1개를 공유한다', async () => {
    let release: () => void = () => {}
    fetchMock.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          release = () => {
            setXsrfCookie('shared')
            resolve()
          }
        }),
    )
    const first = new Headers()
    const second = new Headers()
    const pending = Promise.all([applyCsrfHeader({ method: 'POST', headers: first }), applyCsrfHeader({ method: 'PATCH', headers: second })])
    release()
    await pending
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(first.get('X-XSRF-TOKEN')).toBe('shared')
    expect(second.get('X-XSRF-TOKEN')).toBe('shared')
  })

  it('안전 메서드(GET)는 발급도 헤더도 없다', async () => {
    const headers = new Headers()
    await applyCsrfHeader({ headers })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(headers.get('X-XSRF-TOKEN')).toBeNull()
  })

  it('발급 실패는 경고만 남기고 헤더 없이 진행한다(BE 403으로 드러남)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    fetchMock.mockRejectedValue(new Error('network down'))
    const headers = new Headers()
    await applyCsrfHeader({ method: 'POST', headers })
    expect(headers.get('X-XSRF-TOKEN')).toBeNull()
    expect(warn).toHaveBeenCalled()
  })
})
