import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import LoginPage from '~/pages/login.vue'

// SEC-24: 구매자 로그인 복귀 경로. 이미 인증된 구매자가 /login?redirect=… 로 오면 setup에서 바로 navigateTo(resolveRedirect())한다.
// 백슬래시·인코딩 백슬래시·protocol-relative는 외부 호스트로 해석될 수 있어 홈('/')으로 보내야 한다.
const { routeMock, navigateToMock, fetchMock } = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string>, params: {}, meta: {} },
  navigateToMock: vi.fn(),
  fetchMock: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useAuthStore', () => () => ({ isAuthenticated: true, login: vi.fn(), loginDemo: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)
// 비밀번호 재설정 사용 가능 조회(D-269)는 이 검증과 무관하다 — 비활성으로 고정한다.
mockNuxtImport('usePasswordReset', () => () => ({ fetchAvailability: async () => false, requestReset: vi.fn(), confirmReset: vi.fn() }))

async function redirectFor(redirect: string): Promise<string | undefined> {
  routeMock.query = { redirect }
  await mountSuspended(LoginPage)
  await flushPromises()
  return navigateToMock.mock.calls[0]?.[0]
}

describe('pages/login.vue 복귀 경로 검증(SEC-24)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    fetchMock.mockReset()
    fetchMock.mockResolvedValue({ enabled: false })
  })

  it.each([
    ['/\\evil.com', '백슬래시'],
    ['/%5Cevil.com', '인코딩 백슬래시(대문자)'],
    ['/%5cevil.com', '인코딩 백슬래시(소문자)'],
    ['/mypage/%5C/evil', '경로 중간 인코딩 백슬래시'],
    ['//evil.com', 'protocol-relative'],
    ['https://evil.com', '외부 URL'],
    ['/\t/evil.com', '탭'],
    ['/\n/evil.com', '개행'],
    ['/ /evil.com', '공백'],
    ['/\u0000/evil.com', 'NUL'],
    ['/\u007F/evil.com', 'DEL'],
    ['/%09/evil.com', '디코드 후 탭(이중 인코딩)'],
    ['/%E0%A4%A', '디코드 불가(잘못된 인코딩)'],
  ])('%s(%s) → 홈으로', async (redirect) => {
    expect(await redirectFor(redirect)).toBe('/')
  })

  it('내부 경로 /mypage → 그대로 복귀', async () => {
    expect(await redirectFor('/mypage')).toBe('/mypage')
  })

  it('쿼리 포함 내부 경로(인코딩 문자 포함) → 그대로 복귀', async () => {
    expect(await redirectFor('/search?q=%ED%8C%A8%EB%94%A9')).toBe('/search?q=%ED%8C%A8%EB%94%A9')
  })
})
