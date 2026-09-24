import { describe, it, expect } from 'vitest'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

// 스킨 = 화면만(FE-67). app/skins 아래 뷰는 페이지가 넘긴 vm만 쓰고 데이터 조회·조작·이동을 하지 않는다. ESLint 미도입이라 소스 스캔으로 강제한다.
// Nuxt auto-import로 import 없이도 호출되므로 import 구문과 호출 식별자를 모두 검사한다.
const FRONTEND_DIR = join(dirname(fileURLToPath(import.meta.url)), '../..')
const SKINS_DIR = join(FRONTEND_DIR, 'app/skins')
const SCAN_EXTENSIONS = new Set(['.ts', '.vue'])
// registry는 스킨 조회·대체 규칙, contracts는 페이지↔뷰 타입이라 화면 코드가 아니다 → 검사 제외.
const EXCLUDED = ['registry.ts', `contracts${sep}`]

// 데이터 조회·조작 모듈(리포트 track105 STEP 4·6): 구매자 composables·stores + 관리자·셀러 레이어(useAdminApi·useSellerApi 등).
const DATA_MODULE_DIRS = [
  'app/composables',
  'app/stores',
  'layers/admin/app/composables',
  'layers/admin/app/stores',
  'layers/seller/app/composables',
  'layers/seller/app/stores',
]
const FORBIDDEN_IMPORT_PATTERN =
  /(?:from\s*|import\s*\(\s*)['"]([^'"]*(?:\/composables|\/stores|#layers\/|\/server\/)[^'"]*)['"]/g
const NUXT_DATA_AND_NAVIGATION = ['useFetch', 'useLazyFetch', 'useAsyncData', 'useLazyAsyncData', 'navigateTo', 'useRouter']
const EXPORTED_NAME_PATTERN = /export\s+(?:async\s+)?(?:function|const)\s+(\w+)/g

function listFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return listFiles(path)
    return SCAN_EXTENSIONS.has(path.slice(path.lastIndexOf('.'))) ? [path] : []
  })
}

function exportedDataModuleNames(): string[] {
  return DATA_MODULE_DIRS.map((dir) => join(FRONTEND_DIR, dir))
    .filter((dir) => existsSync(dir))
    .flatMap((dir) => listFiles(dir))
    .flatMap((file) => [...readFileSync(file, 'utf-8').matchAll(EXPORTED_NAME_PATTERN)].map((match) => match[1]!))
}

describe('app/skins 금지 사용(스킨 = 화면만)', () => {
  const forbiddenCalls = [...NUXT_DATA_AND_NAVIGATION, ...exportedDataModuleNames()]
  const forbiddenCallPattern = new RegExp(`(?:\\$fetch\\b|\\b(?:${forbiddenCalls.join('|')})\\s*(?:<[^>()]*>)?\\s*\\()`, 'g')
  const files = listFiles(SKINS_DIR).filter((file) => !EXCLUDED.some((excluded) => relative(SKINS_DIR, file).startsWith(excluded)))

  it('검사 대상 파일과 금지 식별자가 비어 있지 않다', () => {
    expect(files.length).toBeGreaterThan(0)
    expect(forbiddenCalls).toEqual(expect.arrayContaining(['useProductList', 'useCartStore', 'useAuthStore', 'useAdminApi', 'useSellerApi']))
  })

  it('composable·store·API 모듈 import 0건', () => {
    const violations = files.flatMap((file) =>
      [...readFileSync(file, 'utf-8').matchAll(FORBIDDEN_IMPORT_PATTERN)].map((match) => `${relative(SKINS_DIR, file)}: ${match[1]}`),
    )
    expect(violations).toEqual([])
  })

  it('데이터 조회·조작·이동 호출(useFetch·$fetch·useAsyncData·navigateTo·useRouter·composable·store) 0건', () => {
    const violations = files.flatMap((file) =>
      (readFileSync(file, 'utf-8').match(forbiddenCallPattern) ?? []).map((match) => `${relative(SKINS_DIR, file)}: ${match}`),
    )
    expect(violations).toEqual([])
  })
})
