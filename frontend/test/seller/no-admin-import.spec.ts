import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// 격리 원칙(FE-44 §2·recon §10-4 β): layers/seller는 layers/admin의 어떤 파일도 import하지 않는다. ESLint 미도입이라 vitest 소스 스캔으로 CI에 강제한다.
// 검사 대상은 import 구문(정적 `from '…'`·동적 `import('…')`·`require('…')`·CSS `@import '…'`)만 — 주석의 "관리자 X 동형" 언급은 참조가 아니므로 제외한다.
const SELLER_LAYER_DIR = join(dirname(fileURLToPath(import.meta.url)), '../../layers/seller')
const SCAN_EXTENSIONS = new Set(['.ts', '.vue', '.css', '.mjs', '.js'])
const ADMIN_IMPORT_PATTERN = /(?:from\s*|import\s*\(\s*|require\s*\(\s*|@import\s*(?:url\(\s*)?)['"][^'"]*layers\/admin[^'"]*['"]/g

function listFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return listFiles(path)
    return SCAN_EXTENSIONS.has(path.slice(path.lastIndexOf('.'))) ? [path] : []
  })
}

describe('layers/seller → layers/admin import 금지', () => {
  it('셀러 레이어 전 파일에서 layers/admin 경로 import 0건', () => {
    const files = listFiles(SELLER_LAYER_DIR)
    expect(files.length).toBeGreaterThan(0)
    const violations = files.flatMap((file) => {
      const matches = readFileSync(file, 'utf-8').match(ADMIN_IMPORT_PATTERN) ?? []
      return matches.map((match) => `${relative(SELLER_LAYER_DIR, file)}: ${match}`)
    })
    expect(violations).toEqual([])
  })

  it('패턴 자체 검증: import 구문은 잡고 주석 언급은 잡지 않는다', () => {
    const importLine = "import { x } from '#layers/admin/app/lib/jwt'"
    const dynamicLine = "await import('#layers/admin/app/lib/vuetify-styles')"
    const cssLine = "@import '#layers/admin/app/assets/css/admin-vuetify.css'"
    const commentLine = '// layers/admin 파일은 참조하지 않는다(관리자 AdminSidebar 동형)'
    expect(importLine.match(ADMIN_IMPORT_PATTERN)).toHaveLength(1)
    expect(dynamicLine.match(ADMIN_IMPORT_PATTERN)).toHaveLength(1)
    expect(cssLine.match(ADMIN_IMPORT_PATTERN)).toHaveLength(1)
    expect(commentLine.match(ADMIN_IMPORT_PATTERN)).toBeNull()
  })
})
