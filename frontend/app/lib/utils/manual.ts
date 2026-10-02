import type { ManualRegion, ManualSection, ManualStep } from '~/types/manual'

/**
 * 매뉴얼(C8) 순수 함수: 캡처 영역 좌표 → 화면 비율 위치 변환, 목차 앵커·검색 필터. DOM 없이 테스트한다.
 * 좌표를 이미지 크기에 대한 비율(%)로 바꿔 두면 이미지가 어떤 폭으로 그려져도(본문·확대 보기) 같은 자리에 겹친다.
 */

/** 하이라이트가 요소 테두리에 딱 붙지 않게 바깥으로 넓히는 여백(이미지 px). */
export const MANUAL_REGION_PADDING = 6

export interface PercentRect {
  left: number
  top: number
  width: number
  height: number
}

export interface PercentPoint {
  left: number
  top: number
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** 영역을 여백만큼 넓히고 이미지 안으로 자른 픽셀 사각형. 하이라이트 SVG(viewBox = 이미지 px)가 그대로 쓴다. */
export function paddedRegion(region: ManualRegion, imageWidth: number, imageHeight: number, padding = MANUAL_REGION_PADDING): ManualRegion {
  const left = clamp(region.x - padding, 0, imageWidth)
  const top = clamp(region.y - padding, 0, imageHeight)
  const right = clamp(region.x + region.width + padding, 0, imageWidth)
  const bottom = clamp(region.y + region.height + padding, 0, imageHeight)
  return { x: left, y: top, width: right - left, height: bottom - top }
}

/** 영역(이미지 px) → 이미지 크기 대비 비율(%) 사각형. 여백 포함·이미지 밖은 잘라 낸다. */
export function regionToPercentRect(region: ManualRegion, imageWidth: number, imageHeight: number, padding = MANUAL_REGION_PADDING): PercentRect {
  const padded = paddedRegion(region, imageWidth, imageHeight, padding)
  return {
    left: (padded.x / imageWidth) * 100,
    top: (padded.y / imageHeight) * 100,
    width: (padded.width / imageWidth) * 100,
    height: (padded.height / imageHeight) * 100,
  }
}

/**
 * 번호 마커 중심 위치(%). 하이라이트 왼쪽 위 모서리에 걸쳐 둔다 — 영역 안 내용(버튼 글자)을 가리지 않고, 어느 영역의 번호인지 모서리로 읽힌다.
 * 마커 반지름(px)만큼 이미지 안쪽으로 밀어 가장자리 영역에서도 잘리지 않게 한다.
 */
export function markerPosition(region: ManualRegion, imageWidth: number, imageHeight: number, markerRadius = 13, padding = MANUAL_REGION_PADDING): PercentPoint {
  const padded = paddedRegion(region, imageWidth, imageHeight, padding)
  const centerX = clamp(padded.x, markerRadius, imageWidth - markerRadius)
  const centerY = clamp(padded.y, markerRadius, imageHeight - markerRadius)
  return { left: (centerX / imageWidth) * 100, top: (centerY / imageHeight) * 100 }
}

/** 마커 중심 사이 최소 간격(이미지 px). 본문 폭에서 캡처는 약 0.6배로 그려지므로 26px 마커가 겹치지 않으려면 이미지 기준 44px가 필요하다. */
export const MANUAL_MARKER_MIN_GAP = 44

export interface MarkerPoint {
  number: number
  x: number
  y: number
}

function collides(point: MarkerPoint, placed: MarkerPoint[], minGap: number): boolean {
  return placed.some((other) => Math.hypot(point.x - other.x, point.y - other.y) < minGap)
}

/**
 * 가까운 마커 밀어내기. 번호 순서대로 놓으면서 앞서 놓인 마커와 최소 간격보다 가까우면 오른쪽 → 아래 → 왼쪽 → 위 순서로
 * 간격만큼씩 떨어진 자리를 찾아 옮긴다(이미지 안 · 반지름만큼 안쪽). 하이라이트 영역 좌표는 그대로 두고 마커 표시 위치만 바꾼다.
 * 갈 곳이 없으면 원래 자리에 둔다.
 */
export function separateMarkers(points: MarkerPoint[], imageWidth: number, imageHeight: number, minGap = MANUAL_MARKER_MIN_GAP, markerRadius = 13): MarkerPoint[] {
  const directions: [number, number][] = [[1, 0], [0, 1], [-1, 0], [0, -1]]
  const placed: MarkerPoint[] = []
  for (const point of [...points].sort((first, second) => first.number - second.number)) {
    let chosen = point
    if (collides(point, placed, minGap)) {
      const candidates: MarkerPoint[] = []
      for (let ring = 1; ring <= 3; ring += 1) {
        for (const [dx, dy] of directions) {
          candidates.push({
            number: point.number,
            x: clamp(point.x + dx * minGap * ring, markerRadius, imageWidth - markerRadius),
            y: clamp(point.y + dy * minGap * ring, markerRadius, imageHeight - markerRadius),
          })
        }
      }
      chosen = candidates.find((candidate) => !collides(candidate, placed, minGap)) ?? point
    }
    placed.push(chosen)
  }
  return placed
}

/** 본문 앵커 id. 섹션은 `flow-{섹션}`, 단계는 `flow-{섹션}--{단계}` — URL 해시로 직접 진입한다. */
export function manualAnchorId(sectionId: string, stepId?: string): string {
  return stepId === undefined ? `flow-${sectionId}` : `flow-${sectionId}--${stepId}`
}

function normalize(text: string): string {
  return text.toLowerCase().replace(/\s+/g, '')
}

function stepMatches(step: ManualStep, needle: string): boolean {
  return normalize(step.title).includes(needle)
}

/**
 * 목차 검색. 공백·대소문자를 무시하고 섹션 제목·요약 또는 단계 제목에 들어 있으면 남긴다.
 * 섹션 자체가 맞으면 단계를 모두 보이고, 단계만 맞으면 맞은 단계만 보인다. 빈 검색어는 전체.
 */
export function filterManualToc(sections: ManualSection[], query: string): ManualSection[] {
  const needle = normalize(query)
  if (needle === '') return sections
  const result: ManualSection[] = []
  for (const section of sections) {
    const sectionHit = normalize(section.title).includes(needle) || normalize(section.summary).includes(needle)
    if (sectionHit) {
      result.push(section)
      continue
    }
    const steps = section.steps.filter((step) => stepMatches(step, needle))
    if (steps.length > 0) result.push({ ...section, steps })
  }
  return result
}

/** URL 해시(`#flow-…`)가 이 매뉴얼의 섹션·단계 앵커인지. 아니면 null(다른 해시는 무시한다). */
export function resolveManualHash(hash: string, sections: ManualSection[]): string | null {
  const id = decodeURIComponent(hash.replace(/^#/, ''))
  for (const section of sections) {
    if (section.steps.length === 0) continue
    if (manualAnchorId(section.id) === id) return id
    if (section.steps.some((step) => manualAnchorId(section.id, step.id) === id)) return id
  }
  return null
}
