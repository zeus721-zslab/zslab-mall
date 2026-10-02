import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import type { ManualCaptureIndex, ManualDocument } from '~/types/manual'
import { manualAnchorId } from '~/lib/utils/manual'
import { ADMIN_MANUAL } from '#layers/admin/app/lib/admin-manual-content'
import { SELLER_MANUAL } from '#layers/seller/app/lib/seller-manual-content'
import adminCaptures from '~~/public/manual/admin/captures.json'
import sellerCaptures from '~~/public/manual/seller/captures.json'

/**
 * 매뉴얼(C8) 콘텐츠 정합. 콘텐츠(레이어 TS)와 캡처 산출물(public/manual/{역할})이 어긋나면 화면에서 캡처·마커가 조용히 빠진다 —
 * 캡처 id·이미지 파일·영역 좌표·앵커가 서로 맞는지 고정한다.
 */
const MANUALS: { manual: ManualDocument; captures: ManualCaptureIndex }[] = [
  { manual: ADMIN_MANUAL, captures: adminCaptures },
  { manual: SELLER_MANUAL, captures: sellerCaptures },
]

describe.each(MANUALS)('$manual.title', ({ manual, captures }) => {
  const steps = manual.sections.flatMap((section) => section.steps.map((step) => ({ section, step })))

  it('모든 섹션에 본문 단계가 있다(목차 "준비 중" 없음)', () => {
    expect(manual.sections.filter((section) => section.steps.length === 0).map((section) => section.id)).toEqual([])
  })

  it('모든 단계의 캡처 id가 좌표 JSON에 있고 이미지 파일이 있다', () => {
    const missing: string[] = []
    for (const { step } of steps) {
      if (step.captureId === null) {
        missing.push(`${step.id}: captureId 없음`)
        continue
      }
      const capture = captures[step.captureId]
      if (capture === undefined) missing.push(`${step.captureId}: captures.json 없음`)
      else if (!existsSync(resolve(process.cwd(), 'public/manual', manual.role, capture.file))) missing.push(`${capture.file}: 이미지 없음`)
    }
    expect(missing).toEqual([])
  })

  it('모든 콜아웃 번호에 영역 좌표가 있고, 단계마다 2~5개가 1부터 이어진다', () => {
    const problems: string[] = []
    for (const { step } of steps) {
      const numbers = step.callouts.map((callout) => callout.number)
      if (numbers.join() !== numbers.map((_, index) => index + 1).join()) problems.push(`${step.id}: 번호 ${numbers.join()}`)
      if (step.callouts.length < 2 || step.callouts.length > 5) problems.push(`${step.id}: 콜아웃 ${step.callouts.length}개`)
      const regions = step.captureId === null ? undefined : captures[step.captureId]?.regions
      for (const callout of step.callouts) {
        if (regions?.[callout.region] === undefined) problems.push(`${step.captureId}.${callout.region}`)
      }
    }
    expect(problems).toEqual([])
  })

  it('섹션·단계 앵커가 겹치지 않는다', () => {
    const anchors = manual.sections.flatMap((section) => [manualAnchorId(section.id), ...section.steps.map((step) => manualAnchorId(section.id, step.id))])
    expect(new Set(anchors).size).toBe(anchors.length)
  })
})
