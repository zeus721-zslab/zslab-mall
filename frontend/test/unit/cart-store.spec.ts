import { describe, it, expect, beforeEach, vi } from 'vitest'
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

// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn() }))
mockNuxtImport('$fetch', () => fetchMock)

describe('cart 스토어 load (FE-86 비로그인 요청 없음)', () => {
  beforeEach(() => {
    cookieRefs.clear()
    fetchMock.mockReset()
    setActivePinia(createPinia())
  })

  it('비로그인이면 GET /cart를 요청하지 않고 뱃지 개수는 0', async () => {
    const cart = useCartStore()

    await cart.load()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(cart.count).toBe(0)
  })
})
