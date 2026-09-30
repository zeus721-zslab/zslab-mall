import { describe, it, expect } from 'vitest'
import { resolveActiveSection, sectionProgress, type SectionBox } from '~/lib/utils/section-nav'

/**
 * Track 106-2 진행형 섹션 바 계산(순수 함수). 기준선 = 헤더 + 바 아래(여기서는 120px) · 뷰포트 높이 800px.
 */
const ANCHOR = 120
const VIEWPORT = 800

function boxes(descriptionTop: number): SectionBox[] {
  // 설명 2000px · 리뷰 1000px · 질문 300px가 붙어 있다.
  return [
    { id: 'product-description', top: descriptionTop, height: 2000 },
    { id: 'product-reviews', top: descriptionTop + 2000, height: 1000 },
    { id: 'product-questions', top: descriptionTop + 3000, height: 300 },
  ]
}

describe('resolveActiveSection', () => {
  it('어느 섹션도 기준선에 닿지 않으면 null(구매 영역을 보는 중)', () => {
    expect(resolveActiveSection(boxes(500), ANCHOR, VIEWPORT, false)).toBeNull()
  })

  it('윗변이 기준선에 닿은 섹션 중 마지막이 활성', () => {
    expect(resolveActiveSection(boxes(ANCHOR), ANCHOR, VIEWPORT, false)).toBe('product-description')
    expect(resolveActiveSection(boxes(ANCHOR - 2000), ANCHOR, VIEWPORT, false)).toBe('product-reviews')
  })

  it('페이지 끝이면 기준선에 못 올라온 짧은 마지막 섹션도 화면 안에 있으면 활성', () => {
    // 질문 윗변 = 120 - 2700 + 3000 = 420 > 기준선 → 끝이 아니면 리뷰, 끝이면 질문
    const scrolled = boxes(ANCHOR - 2700)
    expect(resolveActiveSection(scrolled, ANCHOR, VIEWPORT, false)).toBe('product-reviews')
    expect(resolveActiveSection(scrolled, ANCHOR, VIEWPORT, true)).toBe('product-questions')
  })
})

describe('sectionProgress', () => {
  const tall: SectionBox = { id: 'product-description', top: 0, height: 2000 }

  it('윗변이 기준선 아래면 0 · 기준선에서 0 · 아랫변이 뷰포트 바닥에 닿으면 1', () => {
    expect(sectionProgress({ ...tall, top: 300 }, ANCHOR, VIEWPORT)).toBe(0)
    expect(sectionProgress({ ...tall, top: ANCHOR }, ANCHOR, VIEWPORT)).toBe(0)
    // 읽을 거리 = 2000 - (800 - 120) = 1320 · 절반 지점
    expect(sectionProgress({ ...tall, top: ANCHOR - 660 }, ANCHOR, VIEWPORT)).toBeCloseTo(0.5)
    expect(sectionProgress({ ...tall, top: ANCHOR - 1320 }, ANCHOR, VIEWPORT)).toBe(1)
    expect(sectionProgress({ ...tall, top: ANCHOR - 5000 }, ANCHOR, VIEWPORT)).toBe(1)
  })

  it('보이는 영역보다 짧은 섹션은 기준선에 닿으면 1', () => {
    expect(sectionProgress({ id: 'product-questions', top: ANCHOR - 1, height: 300 }, ANCHOR, VIEWPORT)).toBe(1)
    expect(sectionProgress({ id: 'product-questions', top: ANCHOR + 1, height: 300 }, ANCHOR, VIEWPORT)).toBe(0)
  })
})
