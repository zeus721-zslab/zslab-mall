import { describe, it, expect, vi } from 'vitest'
import type { NuxtApp } from '#app'
import { ensureSellerVuetify } from '#layers/seller/app/lib/vuetify'

// 1회 설치 가드(관리자 FE-22c 동형·자체 플래그 __sellerVuetifyInstalled): 같은 vueApp에 두 번 호출해도 createVuetify·use는 1회.
// 실제 vuetify 로드는 mock(테스트 환경 CSS 로드 회피). 셀러 자체 테마·defaults가 인스턴스에 실리는지도 본다.
const { createVuetifyMock, vuetifyInstance } = vi.hoisted(() => {
  const vuetifyInstance = { install: vi.fn() }
  return { vuetifyInstance, createVuetifyMock: vi.fn(() => vuetifyInstance) }
})

vi.mock('vuetify', () => ({ createVuetify: createVuetifyMock }))
vi.mock('vuetify/iconsets/mdi-svg', () => ({ aliases: {}, mdi: {} }))
vi.mock('#layers/seller/app/lib/vuetify-styles', () => ({}))

describe('ensureSellerVuetify', () => {
  it('두 번 호출 → createVuetify·vueApp.use 각 1회 · 셀러 자체 테마(primary teal)', async () => {
    const use = vi.fn()
    const nuxtApp = { vueApp: { use } } as unknown as NuxtApp
    await ensureSellerVuetify(nuxtApp)
    await ensureSellerVuetify(nuxtApp)
    expect(createVuetifyMock).toHaveBeenCalledTimes(1)
    expect(use).toHaveBeenCalledTimes(1)
    expect(use).toHaveBeenCalledWith(vuetifyInstance)
    const options = createVuetifyMock.mock.calls[0]?.[0] as { theme: { themes: { light: { colors: { primary: string } } } } }
    expect(options.theme.themes.light.colors.primary).toBe('#0D9488')
  })
})
