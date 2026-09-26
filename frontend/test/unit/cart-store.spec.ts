import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref, type Ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useCartStore } from '~/stores/cart'

// 토큰 쿠키가 없는 상태(비로그인)를 이름별 ref로 만든다(adminAuth-store.spec 선례).
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

describe('cart 스토어 load (FE-86 비로그인 요청 없음)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    fetchMock.mockReset()
    vi.stubGlobal('$fetch', fetchMock)
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('비로그인이면 GET /cart를 요청하지 않고 뱃지 개수는 0', async () => {
    const cart = useCartStore()

    await cart.load()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(cart.count).toBe(0)
  })
})
