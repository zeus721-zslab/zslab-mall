import { defineConfig, devices } from '@playwright/test'

/**
 * 워크스루 전용 Playwright 설정(Track 98 FE-60). 운영 시나리오를 끝까지 실행하며 클릭·입력·화면 이동을 세고(개선 라운드용)
 * 단계마다 스크린샷을 남긴다(매뉴얼용). 기존 e2e(playwright.config.ts·testDir 'e2e')와 디렉토리·파일명이 모두 달라 섞이지 않으며
 * CI(.github/workflows/frontend-ci.yml)는 Playwright를 돌리지 않으므로 CI에도 포함되지 않는다.
 *
 * 실행 전제: 컨테이너 안에서 dev 서버(:3000)가 이미 떠 있어야 한다(webServer 미설정 — 빌드를 유발하지 않는다).
 * 기준 상태는 scripts/walkthrough/restore.py로 되돌린 뒤 실행한다(시나리오가 상태를 전이시키므로 재현성 조건).
 */
export default defineConfig({
  testDir: 'walkthrough',
  // 시나리오 파일은 *.walkthrough.ts — 기본 testMatch(*.spec.ts)와 겹치지 않아 e2e 설정으로는 절대 수집되지 않는다.
  testMatch: '**/*.walkthrough.ts',
  // 계측 재현성: 직렬 실행 1워커·재시도 없음(재시도는 클릭 수를 중복 계상한다).
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  timeout: 120_000,
  outputDir: 'playwright-report/walkthrough-artifacts',
  globalTeardown: './walkthrough/helpers/summary.ts',
  use: {
    baseURL: 'http://localhost:3000',
    viewport: { width: 1440, height: 900 },
    trace: 'on',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
})
