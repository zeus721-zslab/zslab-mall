import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { NuxtApp } from '#app'
import cartLoadPlugin from '~/plugins/cart-load'

// 플러그인이 소비하는 최소 인터페이스만 mock: auth(isAuthenticated·role)·cart(load)·callOnce(즉시 실행).
const { authMock, cartMock, callOnceMock } = vi.hoisted(() => ({
  authMock: { isAuthenticated: false, role: null as string | null },
  cartMock: { load: vi.fn() },
  callOnceMock: vi.fn(async (fn: () => Promise<void>) => fn()),
}))

mockNuxtImport('useAuthStore', () => () => authMock)
mockNuxtImport('useCartStore', () => () => cartMock)
mockNuxtImport('callOnce', () => callOnceMock)

// defineNuxtPlugin은 함수 플러그인을 그대로 반환하므로 nuxtApp 자리에 빈 객체를 넘겨 직접 실행한다.
async function runPlugin(): Promise<void> {
  const plugin = cartLoadPlugin as unknown as (nuxtApp: NuxtApp) => Promise<void>
  await plugin({} as NuxtApp)
}

describe('cart-load 플러그인 (FE-22 D-2 role 가드)', () => {
  beforeEach(() => {
    cartMock.load.mockReset()
    callOnceMock.mockClear()
    authMock.isAuthenticated = false
    authMock.role = null
  })

  it('BUYER 인증 → cart.load 호출', async () => {
    authMock.isAuthenticated = true
    authMock.role = 'BUYER'
    await runPlugin()
    expect(cartMock.load).toHaveBeenCalledTimes(1)
  })

  it('ADMIN 인증 → cart.load 미호출(skip)', async () => {
    authMock.isAuthenticated = true
    authMock.role = 'ADMIN'
    await runPlugin()
    expect(callOnceMock).not.toHaveBeenCalled()
    expect(cartMock.load).not.toHaveBeenCalled()
  })

  it('미인증 → 기존 경로 유지(callOnce 진입·load는 store가 자체 처리)', async () => {
    await runPlugin()
    expect(cartMock.load).toHaveBeenCalledTimes(1)
  })
})
