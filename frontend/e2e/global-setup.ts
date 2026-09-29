import { chromium, type BrowserContext, type FullConfig, type Page, type Request } from '@playwright/test'
import { gotoClientSide } from './helpers/navigation'

/**
 * e2e 전량 실행 전 dev 서버 워밍업(FE-91). dev 서버는 재시작하면 Vite 클라이언트 모듈 변환 결과(메모리)를 잃고 첫 요청 때 다시 변환하는데,
 * 재시작 직후 첫 테스트는 이 변환을 기다리느라 앱 마운트가 12s까지 늦어져 5s expect에서 실패했다(recon-report-e2e-cold.md · 콜드 29.6s vs 웜 1.6s).
 * 여기서 spec들이 실제로 여는 화면을 브라우저 1개로 한 번씩 열어 변환을 끝내 둔다. 방문과 대기만 하고 클릭·폼 제출은 하지 않는다.
 * 로그인은 helpers/login.ts와 같은 BE 로그인 API(데모 계정 = <ROLE>_E2E_* env)로 하고, 이 컨텍스트는 끝나면 닫아 테스트 세션과 섞이지 않는다.
 * 실패하면 원인을 출력하고 throw해 전체 실행을 중단한다(워밍업이 반쯤 된 채 콜드 실패가 섞이면 판정이 흐려지므로).
 *
 * 생략: E2E_SKIP_WARMUP=1이면 건너뛴다. 서버가 이미 웜(직전 전체 실행·워밍업 이후 재시작 없음)일 때 단일 spec을 반복하는 용도로만 쓴다.
 * 재시작 직후 생략하면 첫 테스트가 콜드 트랩(LT-39)으로 실패할 수 있다.
 */

const SKIP_WARMUP_ENV = 'E2E_SKIP_WARMUP'
const DEFAULT_BASE_URL = 'http://localhost:3000'
// 동적 경로([id]) 페이지는 청크 변환만 필요하므로 존재하지 않는 id로 연다(상세 API는 404 → 화면 안내, 데이터 변화 없음).
const PLACEHOLDER_ID = 'warmup-0'
// Playwright networkidle과 같은 기준(500ms 무요청). 폴링 간격·상한은 대기 판정용이며 테스트 타임아웃과 무관하다.
const NETWORK_QUIET_MS = 500
const NETWORK_POLL_MS = 100
const NETWORK_QUIET_TIMEOUT_MS = 30_000

// 로그인 없이 여는 구매자 화면. 상품 상세는 목록의 첫 상품 링크로 이어서 열므로 /products가 마지막이어야 한다.
const BUYER_PUBLIC_PATHS = ['/', '/help', '/products']

// 구매자 로그인이 필요한 화면. 쿼리가 없는 /claims/new·/payment/mock·/checkout/complete는 안내만 렌더하고(제출은 버튼 클릭 시에만) 청크는 같다.
const BUYER_PATHS = [
  '/mypage',
  '/mypage/password',
  `/orders/${PLACEHOLDER_ID}`,
  `/claims/${PLACEHOLDER_ID}`,
  '/claims/new',
  '/payment/mock',
  '/checkout/complete',
]

const ADMIN_PATHS = [
  '/admin',
  '/admin/help',
  '/admin/products',
  '/admin/products/new',
  '/admin/products/categories',
  `/admin/products/${PLACEHOLDER_ID}`,
  '/admin/orders',
  '/admin/orders/claims',
  '/admin/orders/deliveries',
  '/admin/orders/reconciliation',
  `/admin/orders/${PLACEHOLDER_ID}`,
  '/admin/members',
  '/admin/members/withdrawn',
  '/admin/members/admins',
  '/admin/members/sellers',
  `/admin/members/sellers/${PLACEHOLDER_ID}`,
  `/admin/members/${PLACEHOLDER_ID}`,
  '/admin/settlements',
  '/admin/settlements/sellers',
  `/admin/settlements/${PLACEHOLDER_ID}`,
  '/admin/stats/sales',
  '/admin/stats/orders',
  '/admin/stats/members',
]

const SELLER_PATHS = [
  '/seller',
  '/seller/help',
  '/seller/products',
  '/seller/products/new',
  '/seller/products/inventory',
  `/seller/products/${PLACEHOLDER_ID}`,
  '/seller/orders',
  `/seller/orders/${PLACEHOLDER_ID}`,
  '/seller/claims',
  `/seller/claims/${PLACEHOLDER_ID}`,
  '/seller/deliveries',
  '/seller/settlements',
  `/seller/settlements/${PLACEHOLDER_ID}`,
  '/seller/settings/bank-account',
  '/seller/settings/password',
  '/seller/stats/orders',
  '/seller/stats/products',
  '/seller/stats/sales',
]

type WarmupRole = 'BUYER' | 'ADMIN' | 'SELLER'

const CSRF_TOKEN_API_PATH = '/api/v1/auth/csrf'
const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
const XSRF_HEADER_NAME = 'X-XSRF-TOKEN'

interface RoleSession {
  loginApiPath: string
  emailEnv: string
  passwordEnv: string
}

const ROLE_SESSIONS: Record<WarmupRole, RoleSession> = {
  BUYER: { loginApiPath: '/api/v1/auth/buyer/login', emailEnv: 'BUYER_E2E_EMAIL', passwordEnv: 'BUYER_E2E_PASSWORD' },
  ADMIN: { loginApiPath: '/api/v1/admin/auth/login', emailEnv: 'ADMIN_E2E_EMAIL', passwordEnv: 'ADMIN_E2E_PASSWORD' },
  SELLER: { loginApiPath: '/api/v1/seller/auth/login', emailEnv: 'SELLER_E2E_EMAIL', passwordEnv: 'SELLER_E2E_PASSWORD' },
}

interface NuxtRootElement extends Element {
  __vue_app__?: { $nuxt?: { isHydrating?: boolean } }
}

/** 페이지를 새로 열고(SSR) hydration 완료 → 마운트 직후 API·지연 청크 요청이 잦아들 때까지 기다린다. 소요 ms를 돌려준다. */
async function visit(page: Page, path: string): Promise<number> {
  const startedAt = Date.now()
  await page.goto(path)
  await page.waitForFunction(() => {
    const nuxtApp = (document.querySelector('#__nuxt') as NuxtRootElement | null)?.__vue_app__?.$nuxt
    return nuxtApp !== undefined && nuxtApp.isHydrating === false
  })
  await page.waitForLoadState('networkidle')
  return Date.now() - startedAt
}

/**
 * 첫 화면만 새로 열고 나머지는 클라이언트 내비게이션으로 옮겨 다닌다. 공통 모듈 그래프는 첫 화면에서 이미 변환되므로
 * 매 화면 전체 로드(모듈 ~700건 재검증)를 반복하지 않고 라우트 청크만 받게 한다.
 */
async function visitAll(page: Page, inflightRequests: () => Request[], label: string, paths: string[]): Promise<void> {
  const [firstPath, ...restPaths] = paths
  if (firstPath === undefined) return
  console.log(`[warmup] ${label} ${firstPath} ${await visit(page, firstPath)}ms`)
  for (const path of restPaths) {
    const startedAt = Date.now()
    // 이동 전부터 걸려 있던 요청은 이 화면의 대기 대상이 아니다(실측: 앱 매니페스트 dev.json이 끝 이벤트 없이 남아 대기가 끝나지 않았다).
    const preexisting = new Set(inflightRequests())
    await gotoClientSide(page, path)
    // 클라이언트 이동에는 load 수명주기가 없어 waitForLoadState('networkidle')가 즉시 반환된다 — 진행 중 요청을 직접 센다.
    await waitForNetworkQuiet(page, () => inflightRequests().filter((request) => !preexisting.has(request)))
    console.log(`[warmup] ${label} ${path} ${Date.now() - startedAt}ms`)
  }
}

/** 진행 중 요청이 0인 상태가 NETWORK_QUIET_MS 동안 이어질 때까지 기다린다(마운트 후 API·지연 청크 요청 포함). */
async function waitForNetworkQuiet(page: Page, inflightRequests: () => Request[]): Promise<void> {
  const deadline = Date.now() + NETWORK_QUIET_TIMEOUT_MS
  let quietSince = Date.now()
  while (Date.now() < deadline) {
    await page.waitForTimeout(NETWORK_POLL_MS)
    if (inflightRequests().length > 0) quietSince = Date.now()
    else if (Date.now() - quietSince >= NETWORK_QUIET_MS) return
  }
  const pending = inflightRequests().map((request) => `${request.method()} ${request.url()}`)
  throw new Error(`네트워크가 ${NETWORK_QUIET_TIMEOUT_MS}ms 안에 잠잠해지지 않았습니다(${page.url()}) — 진행 중: ${pending.join(', ')}`)
}

/** 페이지의 진행 중 요청을 추적하기 시작하고, 현재 진행 중 요청 목록을 읽는 함수를 돌려준다. */
function trackInflightRequests(page: Page): () => Request[] {
  const inflight = new Set<Request>()
  page.on('request', (request) => inflight.add(request))
  page.on('requestfinished', (request) => inflight.delete(request))
  page.on('requestfailed', (request) => inflight.delete(request))
  return () => [...inflight]
}

/** 상품 상세는 실데이터 id가 있어야 SSR·클라이언트 경로가 끝까지 돈다 — 목록의 첫 상품 링크를 따라간다. */
async function visitFirstProductDetail(page: Page): Promise<void> {
  const href = await page.locator('a[href^="/products/"]').first().getAttribute('href')
  if (!href) throw new Error('상품 목록에 상품 상세 링크가 없습니다(시드 데이터 확인)')
  const elapsed = await visit(page, href)
  console.log(`[warmup] BUYER ${href} ${elapsed}ms`)
}

async function loginAs(context: BrowserContext, baseUrl: string, role: WarmupRole): Promise<boolean> {
  const session = ROLE_SESSIONS[role]
  const email = process.env[session.emailEnv]
  const password = process.env[session.passwordEnv]
  if (!email || !password) {
    // 자격증명이 없으면 해당 역할 spec도 전부 skip되므로(helpers/login.ts) 워밍업도 건너뛴다.
    console.log(`[warmup] ${role} 건너뜀 — ${session.emailEnv} / ${session.passwordEnv} 미설정`)
    return false
  }
  // context.request는 컨텍스트 쿠키 저장소를 공유하므로 BE Set-Cookie(역할 쿠키·XSRF-TOKEN)가 그대로 저장된다(helpers/login.ts와 같은 방식·D-235 F10).
  // 로그인은 CSRF를 검증하므로 인증 전 토큰을 먼저 받아 헤더로 싣는다(D-235 PR3 K7).
  const csrfResponse = await context.request.get(`${baseUrl}${CSRF_TOKEN_API_PATH}`)
  if (!csrfResponse.ok()) throw new Error(`CSRF 토큰 발급 API ${csrfResponse.status()}`)
  const csrfToken = (await context.cookies()).find((cookie) => cookie.name === XSRF_COOKIE_NAME)?.value
  if (!csrfToken) throw new Error(`${XSRF_COOKIE_NAME} 쿠키 없음`)
  const response = await context.request.post(`${baseUrl}${session.loginApiPath}`, {
    data: { email, password },
    headers: { [XSRF_HEADER_NAME]: csrfToken },
  })
  if (!response.ok()) throw new Error(`${role} 로그인 API ${response.status()}`)
  return true
}

export default async function globalSetup(config: FullConfig): Promise<void> {
  if (process.env[SKIP_WARMUP_ENV] === '1') {
    console.log(`[warmup] 워밍업 생략(${SKIP_WARMUP_ENV}=1) — 콜드 서버면 첫 테스트가 실패할 수 있음`)
    return
  }
  const baseUrl = config.projects[0]?.use.baseURL ?? DEFAULT_BASE_URL
  const startedAt = Date.now()
  const browser = await chromium.launch()
  try {
    const context = await browser.newContext({ baseURL: baseUrl })
    const page = await context.newPage()
    const inflightRequests = trackInflightRequests(page)
    await visitAll(page, inflightRequests, 'BUYER', BUYER_PUBLIC_PATHS)
    await visitFirstProductDetail(page)
    // 로그인 화면은 세션이 없을 때만 폼을 렌더하므로 로그인 전에 연다.
    await visitAll(page, inflightRequests, 'BUYER', ['/login'])
    await visitAll(page, inflightRequests, 'ADMIN', ['/admin/login'])
    await visitAll(page, inflightRequests, 'SELLER', ['/seller/login'])
    if (await loginAs(context, baseUrl, 'BUYER')) await visitAll(page, inflightRequests, 'BUYER', BUYER_PATHS)
    if (await loginAs(context, baseUrl, 'ADMIN')) await visitAll(page, inflightRequests, 'ADMIN', ADMIN_PATHS)
    if (await loginAs(context, baseUrl, 'SELLER')) await visitAll(page, inflightRequests, 'SELLER', SELLER_PATHS)
    await context.close()
  } catch (error) {
    console.error('[warmup] 실패 — 전체 실행을 중단합니다:', error)
    throw error
  } finally {
    await browser.close()
  }
  console.log(`[warmup] 완료 ${Date.now() - startedAt}ms`)
}
