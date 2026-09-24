import { defineVitestConfig } from '@nuxt/test-utils/config'
import { configDefaults } from 'vitest/config'

// 테스트 스킨은 classic 고정(FE-70). test-utils가 설정 로드 시 process.env의 NUXT_*를 runtimeConfig에 적용하므로
// 컨테이너가 물려받은 로컬 스킨 env(NUXT_PUBLIC_SKIN=renew)가 테스트에 새지 않도록 설정 로드 전에 덮어쓴다(test.env는 워커 전용이라 늦음).
process.env.NUXT_PUBLIC_SKIN = 'classic'

// nuxt.config.ts의 vite 설정(alias·tailwindcss 등)을 test-utils가 상속하므로 alias 수동 배선 없음.
export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    // 컨테이너 실행 시 첫 파일이 Nuxt 전체 변환을 준비 단계에서 떠안음(측정 10.0~10.4s) — 준비 단계(setupNuxt 훅)만 늘린다(FE-70).
    hookTimeout: 30000,
    // Playwright smoke(e2e/**)는 Vitest가 아니라 playwright test로만 실행한다.
    // 기본 exclude(node_modules·dist 등)를 스프레드로 보존하고 e2e만 추가(기본값 override 방지).
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
