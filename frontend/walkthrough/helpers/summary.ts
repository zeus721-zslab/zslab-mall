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
  lines.push('## 역할 · 구간별')
  lines.push('')
  lines.push('역할 전환 시나리오는 역할별로, 다건 반복 시나리오는 건별로 나눠 센 값이다(구간이 없는 시나리오는 생략).')
  lines.push('')
  lines.push('| 시나리오 | 구간 | 역할 | 클릭 | 입력 | 화면 이동 |')
  lines.push('|---|---|---|---:|---:|---:|')
  for (const row of rows.filter((candidate) => candidate.segments.length > 0)) {
    for (const segment of row.segments) {
      lines.push('| ' + [row.title, segment.label, segment.role, segment.clicks, segment.inputs, segment.navigations].join(' | ') + ' |')
    }
  }

  lines.push('')
  lines.push('## 관찰값')
  lines.push('')
  for (const row of rows.filter((candidate) => Object.keys(candidate.notes).length > 0)) {
    lines.push('- **' + row.title + '** — ' + Object.entries(row.notes).map(([key, value]) => key + ': ' + value).join(' · '))
  }

  lines.push('')
  lines.push('## 브라우저 오류')
  lines.push('')
  const withErrors = rows.filter((candidate) => candidate.errors.length > 0)
  if (withErrors.length === 0) {
    lines.push('없음(pageerror·console.error 0건).')
  } else {
    for (const row of withErrors) {
      lines.push('- **' + row.title + '** (' + row.errors.length + '건)')
      for (const error of row.errors) lines.push('  - ' + error)
    }
  }

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
