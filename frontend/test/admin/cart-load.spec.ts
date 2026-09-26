import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { effectScope, nextTick, reactive } from 'vue'
import type { EffectScope } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { NuxtApp } from '#app'
import cartLoadPlugin from '~/plugins/cart-load'

// 플러그인이 소비하는 최소 인터페이스만 mock: auth(isAuthenticated·role)·cart(load·clear)·callOnce(즉시 실행).
// auth는 인증 전환 watch(FE-86)를 발화시키려고 reactive로 두고 테스트마다 새 객체로 바꾼다.
// 테스트 환경 부팅 때 앱 플러그인도 한 번 실행돼 이전 auth 객체를 watch하므로, 같은 객체를 재사용하면 호출 수가 섞인다.
const { authHolder, cartMock, callOnceMock } = vi.hoisted(() => ({
  authHolder: { current: { isAuthenticated: false, role: null as string | null } },
  cartMock: { load: vi.fn(), clear: vi.fn() },
  callOnceMock: vi.fn(async (fn: () => Promise<void>) => fn()),
}))

mockNuxtImport('useAuthStore', () => () => authHolder.current)
mockNuxtImport('useCartStore', () => () => cartMock)
mockNuxtImport('callOnce', () => callOnceMock)

// 테스트마다 플러그인 watch를 scope로 묶어 다음 테스트로 새지 않게 한다.
let scope: EffectScope

// defineNuxtPlugin은 함수 플러그인을 그대로 반환하므로 nuxtApp 자리에 빈 객체를 넘겨 직접 실행한다.
async function runPlugin(): Promise<void> {
  const plugin = cartLoadPlugin as unknown as (nuxtApp: NuxtApp) => Promise<void>
  scope = effectScope()
  await scope.run(() => plugin({} as NuxtApp))
}

describe('cart-load 플러그인 (FE-22 D-2 role 가드)', () => {
  beforeEach(() => {
    cartMock.load.mockReset()
    cartMock.clear.mockReset()
    callOnceMock.mockClear()
    authHolder.current = reactive({ isAuthenticated: false, role: null as string | null })
  })

  afterEach(() => {
    scope.stop()
  })

  it('BUYER 인증 → cart.load 호출', async () => {
    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'BUYER'
    await runPlugin()
    expect(cartMock.load).toHaveBeenCalledTimes(1)
  })

  it('ADMIN 인증 → cart.load 미호출(skip)', async () => {
    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'ADMIN'
    await runPlugin()
    expect(callOnceMock).not.toHaveBeenCalled()
    expect(cartMock.load).not.toHaveBeenCalled()
  })

  it('미인증 → 기존 경로 유지(callOnce 진입·load는 store가 자체 처리)', async () => {
    await runPlugin()
    expect(cartMock.load).toHaveBeenCalledTimes(1)
  })
})

describe('cart-load 플러그인 인증 전환 watch (FE-86)', () => {
  beforeEach(() => {
    cartMock.load.mockReset()
    cartMock.clear.mockReset()
    callOnceMock.mockClear()
    authHolder.current = reactive({ isAuthenticated: false, role: null as string | null })
  })

  afterEach(() => {
    scope.stop()
  })

  it('미인증으로 시작 → BUYER 로그인 직후 load 1회 추가 호출', async () => {
    await runPlugin()
    cartMock.load.mockClear()

    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'BUYER'
    await nextTick()

    expect(cartMock.load).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).not.toHaveBeenCalled()
  })

  it('BUYER로 시작 → 로그아웃(토큰 해제)되면 clear', async () => {
    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'BUYER'
    await runPlugin()
    cartMock.load.mockClear()

    authHolder.current.isAuthenticated = false
    authHolder.current.role = null
    await nextTick()

    expect(cartMock.clear).toHaveBeenCalledTimes(1)
    expect(cartMock.load).not.toHaveBeenCalled()
  })

  it('인증 전환이 없으면 시작 뒤 load·clear를 추가로 부르지 않는다(하이드레이션 직후 중복 요청 없음)', async () => {
    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'BUYER'
    await runPlugin()
    await nextTick()

    expect(cartMock.load).toHaveBeenCalledTimes(1)
    expect(cartMock.clear).not.toHaveBeenCalled()
  })

  it('로그인 뒤 load 실패는 삼키지 않고 로깅만 한다(렌더 계속)', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    await runPlugin()
    cartMock.load.mockRejectedValueOnce(new Error('boom'))

    authHolder.current.isAuthenticated = true
    authHolder.current.role = 'BUYER'
    await nextTick()
    await nextTick()

    expect(consoleErrorSpy).toHaveBeenCalledTimes(1)
    consoleErrorSpy.mockRestore()
  })
})
