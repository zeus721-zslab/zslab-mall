import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { WalkthroughMetrics } from './walkthrough'

/**
 * 워크스루 globalTeardown(Track 98 FE-60). 시나리오별 metrics.json을 모아 summary.json·summary.md를 만든다.
 * 실행 순서와 무관하게 역할·시나리오명 오름차순으로 정렬해 두 번 실행한 결과를 그대로 비교할 수 있게 한다.
 */
const ROOT = resolve(process.cwd(), 'playwright-report/walkthrough')
const ROLES = ['admin', 'seller', 'buyer'] as const

function collect(): WalkthroughMetrics[] {
  const found: WalkthroughMetrics[] = []
  for (const role of ROLES) {
    const roleDir = resolve(ROOT, role)
    if (!existsSync(roleDir)) continue
    for (const scenario of readdirSync(roleDir).sort()) {
      const file = resolve(roleDir, scenario, 'metrics.json')
      if (!existsSync(file)) continue
      found.push(JSON.parse(readFileSync(file, 'utf-8')) as WalkthroughMetrics)
    }
  }
  return found
}

function toMarkdown(rows: WalkthroughMetrics[]): string {
  const lines = [
    '# 워크스루 계측 요약',
    '',
    '생성: ' + new Date().toISOString() + ' · 계측 정의는 walkthrough/helpers/walkthrough.ts 주석 참조.',
    '',
    '| 역할 | 시나리오 | 클릭 | 입력 | 화면 이동 | 스크린샷 | 소요(s) |',
    '|---|---|---:|---:|---:|---:|---:|',
  ]
  for (const row of rows) {
    lines.push('| ' + [row.role, row.title, row.clicks, row.inputs, row.navigations, row.shots,
      (row.durationMs / 1000).toFixed(1)].join(' | ') + ' |')
  }
  const total = rows.reduce(
    (acc, row) => ({ clicks: acc.clicks + row.clicks, inputs: acc.inputs + row.inputs, navigations: acc.navigations + row.navigations }),
    { clicks: 0, inputs: 0, navigations: 0 },
  )
  lines.push('| **합계** | ' + rows.length + '개 시나리오 | **' + total.clicks + '** | **' + total.inputs + '** | **' + total.navigations + '** | | |')
  lines.push('')
  lines.push('## 단계')
  for (const row of rows) {
    lines.push('')
    lines.push('### ' + row.role + ' / ' + row.title)
    row.steps.forEach((step, index) => lines.push((index + 1) + '. ' + step))
  }
  lines.push('')
  return lines.join('\n')
}

export default async function globalTeardown(): Promise<void> {
  const rows = collect()
  if (rows.length === 0) return
  writeFileSync(resolve(ROOT, 'summary.json'), JSON.stringify(rows, null, 2) + '\n', 'utf-8')
  writeFileSync(resolve(ROOT, 'summary.md'), toMarkdown(rows), 'utf-8')
  const total = rows.reduce((acc, row) => acc + row.clicks, 0)
  console.log('[walkthrough] 시나리오 ' + rows.length + '개 · 총 클릭 ' + total + ' → playwright-report/walkthrough/summary.md')
}
