import { defineVitestConfig } from '@nuxt/test-utils/config'

// nuxt.config.ts의 vite 설정(alias·tailwindcss 등)을 test-utils가 상속하므로 alias 수동 배선 없음.
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
  },
})
