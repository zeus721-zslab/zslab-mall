import { defineVitestConfig } from '@nuxt/test-utils/config'
import { configDefaults } from 'vitest/config'

// nuxt.config.ts의 vite 설정(alias·tailwindcss 등)을 test-utils가 상속하므로 alias 수동 배선 없음.
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    // Playwright smoke(e2e/**)는 Vitest가 아니라 playwright test로만 실행한다.
    // 기본 exclude(node_modules·dist 등)를 스프레드로 보존하고 e2e만 추가(기본값 override 방지).
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
