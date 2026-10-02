import { defineConfig, devices } from '@playwright/test'

/**
 * 매뉴얼 캡처 전용 Playwright 설정(C8 P1). 워크스루 체계(playwright.walkthrough.config.ts)와 같은 뷰포트·직렬 실행을 쓰되,
 * 계측(metrics·summary)은 만들지 않고 public/manual/{역할}/ 에 WebP + captures.json만 남긴다.
 * 시나리오 파일은 walkthrough/manual/*.manual.ts — 워크스루(*.walkthrough.ts)·e2e(*.spec.ts) 어느 설정으로도 수집되지 않는다.
 *
 * 실행 전제: 컨테이너 dev 서버(:3000) 기동 · 로컬 시드(scripts/demo-seed/seed.py master → orders 이상, run_seed_local.py 래퍼) 적용.
 * 실행(컨테이너 · 자격증명 env 매핑은 CLAUDE-DEV 규정 명령과 같다):
 *   docker exec zslab_mall_frontend sh -c 'export ADMIN_E2E_EMAIL=$NUXT_ADMIN_DEMO_EMAIL ADMIN_E2E_PASSWORD=$NUXT_ADMIN_DEMO_PASSWORD SELLER_E2E_EMAIL=$NUXT_SELLER_DEMO_EMAIL SELLER_E2E_PASSWORD=$NUXT_SELLER_DEMO_PASSWORD; npx playwright test --config=playwright.manual.config.ts'
 */
export default defineConfig({
  testDir: 'walkthrough/manual',
  testMatch: '**/*.manual.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  timeout: 60_000,
  outputDir: 'playwright-report/manual-artifacts',
  use: {
    baseURL: 'http://localhost:3000',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 },
    },
  ],
})
