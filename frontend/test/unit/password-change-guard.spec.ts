import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import { resolvePasswordChangeRedirect } from '~/lib/password-change-guard'
import passwordChangeMiddleware from '~/middleware/password-change.global'

// Track 84: 비밀번호 변경 강제 — 순수 판정 함수 + 전역 미들웨어 배선(auth store의 isAuthenticated·passwordChangeRequired만 본다).
const REDIRECT = '/mypage/password?reason=temporary'

describe('resolvePasswordChangeRedirect', () => {
  it('강제 상태 + 로그인 중 → 다른 경로는 변경 페이지(사유 query)로', () => {
    expect(resolvePasswordChangeRedirect({ path: '/', authenticated: true, required: true })).toBe(REDIRECT)
    expect(resolvePasswordChangeRedirect({ path: '/mypage', authenticated: true, required: true })).toBe(REDIRECT)
    expect(resolvePasswordChangeRedirect({ path: '/products/prd_1', authenticated: true, required: true })).toBe(REDIRECT)
  })

  it('변경 페이지·로그인 페이지·관리자 영역은 통과', () => {
    expect(resolvePasswordChangeRedirect({ path: '/mypage/password', authenticated: true, required: true })).toBeNull()
    expect(resolvePasswordChangeRedirect({ path: '/login', authenticated: true, required: true })).toBeNull()
    expect(resolvePasswordChangeRedirect({ path: '/admin/orders', authenticated: true, required: true })).toBeNull()
  })

  it('강제 상태 없음 또는 미로그인 → 통과', () => {
    expect(resolvePasswordChangeRedirect({ path: '/mypage', authenticated: true, required: false })).toBeNull()
    expect(resolvePasswordChangeRedirect({ path: '/mypage', authenticated: false, required: true })).toBeNull()
  })
})

const { authMock, navigateToMock } = vi.hoisted(() => ({
  authMock: { isAuthenticated: true, passwordChangeRequired: false },
  navigateToMock: vi.fn(),
}))
mockNuxtImport('useAuthStore', () => () => authMock)
mockNuxtImport('navigateTo', () => navigateToMock)

function route(path: string, fullPath = path): RouteLocationNormalized {
  return { path, fullPath } as RouteLocationNormalized
}

describe('password-change.global 미들웨어', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    authMock.isAuthenticated = true
    authMock.passwordChangeRequired = false
  })

  it('상태 있음 → 마이페이지 이동을 변경 페이지로 리다이렉트', () => {
    authMock.passwordChangeRequired = true
    passwordChangeMiddleware(route('/mypage'), route('/'))
    expect(navigateToMock).toHaveBeenCalledWith(REDIRECT)
  })

  it('상태 있음이라도 변경 페이지(사유 query 포함)·로그인 페이지는 통과(리다이렉트 루프 없음)', () => {
    authMock.passwordChangeRequired = true
    passwordChangeMiddleware(route('/mypage/password', REDIRECT), route('/'))
    passwordChangeMiddleware(route('/login'), route('/'))
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('상태 없음 → 통과', () => {
    passwordChangeMiddleware(route('/mypage'), route('/'))
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
