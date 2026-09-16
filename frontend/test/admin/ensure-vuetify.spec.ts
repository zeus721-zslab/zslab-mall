import { describe, it, expect, vi } from 'vitest'
import type { NuxtApp } from '#app'
import { ensureVuetify } from '#layers/admin/app/lib/vuetify'

// 1회 설치 가드(FE-22c): 같은 vueApp에 두 번 호출해도 createVuetify·use는 1회. 실제 vuetify 로드는 mock(테스트 환경 CSS 로드 회피).
const { createVuetifyMock, vuetifyInstance } = vi.hoisted(() => {
  const vuetifyInstance = { install: vi.fn() }
  return { vuetifyInstance, createVuetifyMock: vi.fn(() => vuetifyInstance) }
})

vi.mock('vuetify', () => ({ createVuetify: createVuetifyMock }))
vi.mock('vuetify/iconsets/mdi-svg', () => ({ aliases: {}, mdi: {} }))
vi.mock('#layers/admin/app/lib/vuetify-styles', () => ({}))

describe('ensureVuetify', () => {
  it('두 번 호출 → createVuetify·vueApp.use 각 1회', async () => {
    const use = vi.fn()
    const nuxtApp = { vueApp: { use } } as unknown as NuxtApp
    await ensureVuetify(nuxtApp)
    await ensureVuetify(nuxtApp)
    expect(createVuetifyMock).toHaveBeenCalledTimes(1)
    expect(use).toHaveBeenCalledTimes(1)
    expect(use).toHaveBeenCalledWith(vuetifyInstance)
  })
})
