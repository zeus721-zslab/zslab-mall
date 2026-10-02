import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

// ManualShell의 스크롤 위치 감지는 resolveActiveSection(ManualShell.vue:40)으로 활성 단계를 정한다. 테스트 DOM에는 레이아웃이 없으므로
// 이 함수를 스텁해 "지금 스크롤 위치에서 보이는 단계"를 테스트가 정한다(이 파일에서 section-nav를 쓰는 곳은 ManualShell뿐).
const scrollSpy = vi.hoisted(() => {
  const state: { visibleAnchor: string | null } = { visibleAnchor: null }
  return state
})
vi.mock('~/lib/utils/section-nav', () => ({
  resolveActiveSection: () => scrollSpy.visibleAnchor,
  sectionProgress: () => 0,
}))
import type { ManualDocument, ManualSection } from '~/types/manual'
import {
  filterManualToc,
  manualAnchorId,
  markerPosition,
  paddedRegion,
  regionToPercentRect,
  resolveManualHash,
  separateMarkers,
} from '~/lib/utils/manual'
import ManualCaptureFrame from '~/components/common/manual/ManualCaptureFrame.vue'
import ManualShell from '~/components/common/manual/ManualShell.vue'
import ManualToc from '~/components/common/manual/ManualToc.vue'

/**
 * 매뉴얼(C8) 순수 함수·영역 표시. 이미지 1000×500 기준 — 좌표를 비율(%)로 바꿔 두면 이미지가 어떤 폭으로 그려져도 같은 자리에 겹친다.
 */
const WIDTH = 1000
const HEIGHT = 500

describe('콜아웃 좌표 → 오버레이 위치', () => {
  it('영역을 여백(6px)만큼 넓혀 이미지 크기 대비 비율로 바꾼다', () => {
    const rect = regionToPercentRect({ x: 106, y: 56, width: 188, height: 88 }, WIDTH, HEIGHT)
    expect(rect).toEqual({ left: 10, top: 10, width: 20, height: 20 })
  })

  it('이미지 가장자리를 넘는 여백은 잘라 이미지 안에 둔다', () => {
    expect(paddedRegion({ x: 2, y: 490, width: 50, height: 10 }, WIDTH, HEIGHT)).toEqual({ x: 0, y: 484, width: 58, height: 16 })
  })

  it('번호 마커는 하이라이트 왼쪽 위 모서리에 둔다', () => {
    expect(markerPosition({ x: 406, y: 206, width: 100, height: 40 }, WIDTH, HEIGHT)).toEqual({ left: 40, top: 40 })
  })

  it('모서리가 이미지 가장자리면 마커 반지름만큼 안쪽으로 민다(잘리지 않게)', () => {
    const point = markerPosition({ x: 0, y: 0, width: 50, height: 20 }, WIDTH, HEIGHT)
    expect(point.left).toBeCloseTo(1.3)
    expect(point.top).toBeCloseTo(2.6)
  })

  it('가까운 마커는 최소 간격만큼 밀어내고, 먼 마커와 하이라이트 좌표 원본은 그대로 둔다', () => {
    const points = [
      { number: 1, x: 500, y: 200 },
      { number: 2, x: 510, y: 205 },
      { number: 3, x: 100, y: 100 },
    ]
    const separated = separateMarkers(points, WIDTH, HEIGHT, 44)
    expect(separated.find((point) => point.number === 1)).toEqual({ number: 1, x: 500, y: 200 })
    expect(separated.find((point) => point.number === 2)).toEqual({ number: 2, x: 554, y: 205 })
    expect(separated.find((point) => point.number === 3)).toEqual({ number: 3, x: 100, y: 100 })
    expect(points[1]).toEqual({ number: 2, x: 510, y: 205 })
  })

  it('같은 영역은 이미지 표시 크기와 무관하게 같은 비율 위치다(원본 · 절반 크기 캡처 좌표)', () => {
    const full = regionToPercentRect({ x: 206, y: 106, width: 88, height: 38 }, WIDTH, HEIGHT, 0)
    const half = regionToPercentRect({ x: 103, y: 53, width: 44, height: 19 }, WIDTH / 2, HEIGHT / 2, 0)
    expect(half).toEqual(full)
  })
})

describe('ManualCaptureFrame 오버레이', () => {
  const capture = {
    file: 'sample.webp', width: WIDTH, height: HEIGHT, bytes: 1, hash: 'h',
    regions: { save: { x: 406, y: 206, width: 100, height: 40 } },
  }
  const callouts = [
    { number: 1, region: 'save', label: '저장', description: '저장 버튼' },
    { number: 2, region: 'missing', label: '없는 영역', description: '캡처에 없는 영역' },
  ]

  it('마커를 비율 위치에 그리고, 캡처에 없는 영역의 마커는 그리지 않는다', async () => {
    const wrapper = await mountSuspended(ManualCaptureFrame, { props: { capture, src: '/x.webp', alt: '예시', callouts, active: null } })
    const pin = wrapper.get('[data-testid="manual-pin-1"]')
    expect(pin.attributes('style')).toContain('left: 40%')
    expect(pin.attributes('style')).toContain('top: 40%')
    expect(wrapper.find('[data-testid="manual-pin-2"]').exists()).toBe(false)
    expect(wrapper.get('svg').attributes('viewBox')).toBe(`0 0 ${WIDTH} ${HEIGHT}`)
  })

  it('활성 번호가 있으면 스포트라이트를 켜고, 마커 hover·focus는 활성 번호를 알린다', async () => {
    const wrapper = await mountSuspended(ManualCaptureFrame, { props: { capture, src: '/x.webp', alt: '예시', callouts, active: 1 } })
    expect(wrapper.get('[data-testid="manual-capture-frame"]').classes()).toContain('manual-frame--spotlight')
    await wrapper.get('[data-testid="manual-pin-1"]').trigger('focus')
    expect(wrapper.emitted('update:active')?.[0]).toEqual([1])
    await wrapper.get('[data-testid="manual-pin-1"]').trigger('mouseleave')
    expect(wrapper.emitted('update:active')?.[1]).toEqual([null])
  })
})

const SECTIONS: ManualSection[] = [
  {
    id: 'claim', title: '클레임 처리', summary: '취소·반품·교환 요청 처리',
    steps: [
      { id: 'bulk', title: '승인 제안을 골라 한 번에 승인', paragraphs: [], captureId: null, captureAlt: '', callouts: [], warnings: [], rules: [] },
      { id: 'inspect', title: '회수품 검수', paragraphs: [], captureId: null, captureAlt: '', callouts: [], warnings: [], rules: [] },
    ],
  },
  { id: 'settlement', title: '정산', summary: '월별 정산 생성, 확정, 지급완료.', steps: [] },
]

describe('목차 앵커·검색', () => {
  it('섹션·단계 앵커 id', () => {
    expect(manualAnchorId('claim')).toBe('flow-claim')
    expect(manualAnchorId('claim', 'bulk')).toBe('flow-claim--bulk')
  })

  it('빈 검색어는 전체', () => {
    expect(filterManualToc(SECTIONS, '  ')).toBe(SECTIONS)
  })

  it('단계 제목만 맞으면 맞은 단계만 남긴다(공백 무시)', () => {
    const result = filterManualToc(SECTIONS, '일괄승인')
    expect(result).toHaveLength(0)
    const byStep = filterManualToc(SECTIONS, '한번에')
    expect(byStep).toHaveLength(1)
    expect(byStep[0]!.steps.map((step) => step.id)).toEqual(['bulk'])
  })

  it('섹션 제목·요약이 맞으면 섹션의 단계를 모두 남긴다 · 본문 없는 흐름도 검색된다', () => {
    expect(filterManualToc(SECTIONS, '반품')[0]!.steps).toHaveLength(2)
    expect(filterManualToc(SECTIONS, '지급').map((section) => section.id)).toEqual(['settlement'])
  })

  it('맞는 것이 없으면 빈 목록', () => {
    expect(filterManualToc(SECTIONS, '쿠폰')).toEqual([])
  })

  it('URL 해시는 본문이 있는 섹션·단계 앵커만 인정한다', () => {
    expect(resolveManualHash('#flow-claim--inspect', SECTIONS)).toBe('flow-claim--inspect')
    expect(resolveManualHash('#flow-claim', SECTIONS)).toBe('flow-claim')
    expect(resolveManualHash('#flow-settlement', SECTIONS)).toBeNull()
    expect(resolveManualHash('#top', SECTIONS)).toBeNull()
  })
})

describe('목차 검색 입력(한글 IME)', () => {
  it('조합 중 입력 이벤트에서도 검색어가 바로 필터에 반영된다', async () => {
    const wrapper = await mountSuspended(ManualToc, { props: { sections: SECTIONS, activeId: null } })
    const input = wrapper.get('[data-testid="manual-toc-search"]')
    // 조합 시작 뒤의 input은 v-model이면 무시된다 — 조합이 끝나지 않은 상태에서 필터가 바뀌어야 한다.
    await input.trigger('compositionstart')
    ;(input.element as HTMLInputElement).value = '지급'
    await input.trigger('input', { isComposing: true })
    expect(wrapper.find('[data-testid="manual-toc-section-settlement"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="manual-toc-section-claim"]').exists()).toBe(false)
  })
})

describe('목차 클릭 이동 중 활성 표시', () => {
  const manualDocument: ManualDocument = { role: 'admin', title: '테스트 매뉴얼', intro: '', sections: SECTIONS }

  /** 목차에서 지금 위치로 표시된(aria-current) 항목의 testid. */
  function activeStep(root: Element): string | null {
    return root.querySelector('[aria-current="location"]')?.getAttribute('data-testid') ?? null
  }

  async function nextFrame(): Promise<void> {
    await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
  }

  it('스크롤이 지나가는 동안 대상에 고정되고, 스크롤이 끝나면 위치 감지를 다시 한다', async () => {
    const wrapper = await mountSuspended(ManualShell, { props: { document: manualDocument, captures: {} } })

    // (a) 클릭 직후 활성 = 대상
    await wrapper.get('[data-testid="manual-toc-step-claim-bulk"]').trigger('click')
    expect(activeStep(wrapper.element)).toBe('manual-toc-step-claim-bulk')

    // (b) 이동 중 다른 단계(검수)를 지나가는 스크롤이 와도 대상 유지
    scrollSpy.visibleAnchor = manualAnchorId('claim', 'inspect')
    window.dispatchEvent(new Event('scroll'))
    await nextFrame()
    await wrapper.vm.$nextTick()
    expect(activeStep(wrapper.element)).toBe('manual-toc-step-claim-bulk')

    // (c) 스크롤이 끝나면(scrollend) 다음 스크롤부터 위치 감지가 다시 활성을 정한다
    window.dispatchEvent(new Event('scrollend'))
    window.dispatchEvent(new Event('scroll'))
    await nextFrame()
    await wrapper.vm.$nextTick()
    expect(activeStep(wrapper.element)).toBe('manual-toc-step-claim-inspect')
    wrapper.unmount()
  })
})
