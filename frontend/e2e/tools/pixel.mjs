// 사용자 화면 픽셀 회귀 도구(FE-22b 방식 정본화·FE-24). 컨테이너 내부에서 실행한다(dev 서버 :3000·node_modules 볼륨).
//   pnpm pixel capture <name>          → playwright-report/pixel-baseline/<name>/ 에 12장(6페이지 × desktop/mobile)
//   pnpm pixel compare <nameA> <nameB> → 동일 파일명 PNG 픽셀 비교, 상이 픽셀은 <nameB>-diff/ 에 빨강으로 저장
// 캡처 규칙(실측 트랩 대응·recon-report-fe-22b §3-1): 페이지별 새 브라우저 컨텍스트(NanumGothic 폴백 글리프 레이스 회피)
// + 문서 높이로 뷰포트 고정 후 fullPage:false(fullPage 리사이즈 직후 폰트 재래스터 레이스 회피).
import { chromium } from '@playwright/test'
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const OUTPUT_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../playwright-report/pixel-baseline')
const BASE_URL = 'http://localhost:3000'
const VIEWPORTS = { desktop: { width: 1280, height: 800 }, mobile: { width: 390, height: 844 } }
const PAGES = [
  { name: 'home', path: '/' },
  { name: 'products', path: '/products' },
  { name: 'product-detail', path: '/products/prd_01M2K01B3GVZXNMTP8EHA07D85' },
  { name: 'login', path: '/login' },
  { name: 'cart', path: '/cart', login: true },
  { name: 'mypage', path: '/mypage', login: true },
]
const SCROLL_STEP_PX = 400
const SCROLL_PAUSE_MS = 50
const SETTLE_MS = 300
const VIEWPORT_RESIZE_SETTLE_MS = 500

/** pngjs는 playwright-core utilsBundle에 번들돼 있어 별도 의존성 없이 재사용한다(isolated linker라 @playwright/test → playwright → playwright-core로 해석). */
function loadPng() {
  const testRequire = createRequire(import.meta.url)
  const playwrightRequire = createRequire(testRequire.resolve('@playwright/test'))
  const coreRequire = createRequire(playwrightRequire.resolve('playwright'))
  return coreRequire('playwright-core/lib/utilsBundle').PNG
}

async function capture(name) {
  if (!name) throw new Error('사용법: pixel capture <name>')
  const outDir = resolve(OUTPUT_ROOT, name)
  mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch()
  const pageErrors = []
  for (const [viewportName, viewport] of Object.entries(VIEWPORTS)) {
    for (const target of PAGES) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' })
      const page = await context.newPage()
      page.on('pageerror', (error) => pageErrors.push(`${viewportName}/${target.name}: ${String(error)}`))
      if (target.login) {
        await page.goto(`${BASE_URL}/login`)
        await page.waitForLoadState('networkidle') // hydration 전 클릭은 무시됨
        await page.getByRole('button', { name: '데모 계정으로 둘러보기' }).click()
        await page.waitForURL((url) => !url.pathname.startsWith('/login'))
      }
      await page.goto(`${BASE_URL}${target.path}`)
      await page.waitForLoadState('networkidle')
      // lazy 이미지 전량 로드
      await page.evaluate(
        async ({ step, pause }) => {
          for (let y = 0; y < document.body.scrollHeight; y += step) {
            window.scrollTo(0, y)
            await new Promise((done) => setTimeout(done, pause))
          }
          window.scrollTo(0, 0)
        },
        { step: SCROLL_STEP_PX, pause: SCROLL_PAUSE_MS },
      )
      await page.waitForFunction(() => Array.from(document.images).every((img) => img.complete && img.naturalWidth > 0))
      await page.mouse.move(0, 0)
      await page.evaluate(() => Promise.all([document.fonts.load('400 16px "Pretendard Variable"'), document.fonts.load('700 16px "Pretendard Variable"')]))
      await page.evaluate(() => document.fonts.ready)
      await page.addStyleTag({ content: '#nuxt-devtools-container, nuxt-devtools-frame, [id^="nuxt-devtools"] { display: none !important }' })
      await page.waitForTimeout(SETTLE_MS)
      const documentHeight = await page.evaluate(() => document.documentElement.scrollHeight)
      await page.setViewportSize({ width: viewport.width, height: documentHeight })
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(VIEWPORT_RESIZE_SETTLE_MS)
      await page.screenshot({ path: resolve(outDir, `${target.name}-${viewportName}.png`), fullPage: false, animations: 'disabled' })
      await context.close()
    }
  }
  await browser.close()
  console.log(`captured ${Object.keys(VIEWPORTS).length * PAGES.length} → ${outDir}`)
  if (pageErrors.length > 0) {
    console.error('pageerrors', pageErrors)
    process.exitCode = 1
  }
}

function compare(nameA, nameB) {
  if (!nameA || !nameB) throw new Error('사용법: pixel compare <nameA> <nameB>')
  const PNG = loadPng()
  const dirA = resolve(OUTPUT_ROOT, nameA)
  const dirB = resolve(OUTPUT_ROOT, nameB)
  const diffDir = resolve(OUTPUT_ROOT, `${nameB}-diff`)
  let totalDiff = 0
  for (const fileName of readdirSync(dirA).filter((file) => file.endsWith('.png')).sort()) {
    const imageA = PNG.sync.read(readFileSync(resolve(dirA, fileName)))
    const imageB = PNG.sync.read(readFileSync(resolve(dirB, fileName)))
    if (imageA.width !== imageB.width || imageA.height !== imageB.height) {
      console.log(`${fileName}: SIZE DIFF ${imageA.width}x${imageA.height} vs ${imageB.width}x${imageB.height}`)
      totalDiff += 1
      continue
    }
    let differing = 0
    const diffImage = new PNG({ width: imageA.width, height: imageA.height })
    for (let index = 0; index < imageA.data.length; index += 4) {
      const differs =
        imageA.data[index] !== imageB.data[index] ||
        imageA.data[index + 1] !== imageB.data[index + 1] ||
        imageA.data[index + 2] !== imageB.data[index + 2]
      if (differs) differing++
      // diff 이미지: 원본을 연하게, 상이 픽셀은 빨강
      for (let channel = 0; channel < 3; channel++) {
        const original = 200 + Math.round(imageA.data[index + channel] * 0.2)
        diffImage.data[index + channel] = differs ? (channel === 0 ? 255 : 0) : original
      }
      diffImage.data[index + 3] = 255
    }
    if (differing > 0) {
      mkdirSync(diffDir, { recursive: true })
      writeFileSync(resolve(diffDir, fileName), PNG.sync.write(diffImage))
    }
    totalDiff += differing
    const total = imageA.width * imageA.height
    console.log(`${fileName}: ${imageA.width}x${imageA.height} diff=${differing} (${((differing / total) * 100).toFixed(3)}%)`)
  }
  if (totalDiff > 0) process.exitCode = 1
}

const [command, ...args] = process.argv.slice(2)
if (command === 'capture') await capture(args[0])
else if (command === 'compare') compare(args[0], args[1])
else {
  console.error('사용법: pixel capture <name> | pixel compare <nameA> <nameB>')
  process.exitCode = 2
}
