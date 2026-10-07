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

// WCAG 2.x 본문 글자 최소 대비(AA). 테마 색은 글자·outlined 테두리·채움 버튼 글자 바탕으로 쓰여 흰 바탕 기준 이 값을 넘어야 한다(PF-02·PF-18).
const MIN_TEXT_CONTRAST = 4.5
const WHITE = '#FFFFFF'

function relativeLuminance(hex: string): number {
  const channels = [1, 3, 5].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255)
  const [red, green, blue] = channels.map((value) => (value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
  return 0.2126 * (red ?? 0) + 0.7152 * (green ?? 0) + 0.0722 * (blue ?? 0)
}

function contrastRatio(foreground: string, background: string): number {
  const [lighter, darker] = [relativeLuminance(foreground), relativeLuminance(background)].sort((left, right) => right - left)
  return ((lighter ?? 0) + 0.05) / ((darker ?? 0) + 0.05)
}

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
    expect(options.theme.themes.light.colors.primary).toBe('#0F766E')
  })

  it('primary·error·warning·success는 흰 바탕 대비 4.5 이상(PF-02·PF-18)', async () => {
    const nuxtApp = { vueApp: { use: vi.fn() } } as unknown as NuxtApp
    await ensureSellerVuetify(nuxtApp)
    const options = createVuetifyMock.mock.calls.at(-1)?.[0] as { theme: { themes: { light: { colors: Record<string, string> } } } }
    const colors = options.theme.themes.light.colors
    for (const name of ['primary', 'error', 'warning', 'success']) {
      expect(contrastRatio(colors[name] ?? WHITE, WHITE), name).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST)
    }
  })
})
