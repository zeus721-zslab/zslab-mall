import { defineConfig, devices } from '@playwright/test'

/**
 * Browser/SSR Smoke 전용 설정(FE-15 STEP3). E2E(인증·click→API·쓰기)가 아니라 goto→SSR 렌더 assert만 수행한다.
 *
 * 토폴로지: SSR은 frontend 컨테이너 내부에서 실행돼야 backend(mall-backend:8080·host 미노출)에 도달한다.
 * - 로컬: 이미 구동 중인 컨테이너 dev 서버(:3000)를 reuseExistingServer로 재사용한다(webServer.command 미실행).
 * - clean/CI: webServer.command로 build 후 preview를 :3000에 기동한다(반드시 backend 도달 가능한 환경=컨테이너/CI에서 실행).
 */
export default defineConfig({
  testDir: 'e2e',
  // Smoke 1개라 병렬·재시도는 최소. CI에서만 실패 시 1회 재시도.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    // clean/CI용 정본: 프로덕션 build 후 preview. 로컬은 아래 reuseExistingServer로 dev 서버를 재사용해 이 명령을 건너뛴다.
    command: 'pnpm build && pnpm preview',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    // nuxt build가 오래 걸릴 수 있어 넉넉히(3분).
    timeout: 180_000,
  },
})
