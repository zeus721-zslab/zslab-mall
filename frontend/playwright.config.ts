import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 설정. FE-15 STEP3의 Browser/SSR Smoke(smoke.spec) 1개로 시작해 지금은 관리자(admin-*)·셀러(seller-shell)·구매자(claims·password-change) E2E까지
 * e2e/ 전체가 이 설정으로 돈다. 세션은 e2e/helpers/login.ts(BE 로그인 API → 쿠키)로 심고 쓰기 API는 각 spec이 page.route로 mock한다.
 * 역할별 자격증명은 ADMIN_E2E_*·SELLER_E2E_*·BUYER_E2E_* env로 주입한다(미설정 케이스는 skip).
 *
 * 토폴로지: SSR은 frontend 컨테이너 내부에서 실행돼야 backend(mall-backend:8080·host 미노출)에 도달한다.
 * - 로컬: 이미 구동 중인 컨테이너 dev 서버(:3000)를 reuseExistingServer로 재사용한다(webServer.command 미실행).
 * - clean/CI: webServer.command로 build 후 preview를 :3000에 기동한다(반드시 backend 도달 가능한 환경=컨테이너/CI에서 실행).
 */
export default defineConfig({
  testDir: 'e2e',
  // 파일 단위 병렬(파일 안은 직렬). CI에서만 실패 시 1회 재시도.
  // workers는 기본값(논리 코어 50%). 데모 로그인 라우트는 rate limit(60s/30회)이 있으므로 데모 검증 케이스를 늘릴 때는 워커 수를 고려할 것.
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
