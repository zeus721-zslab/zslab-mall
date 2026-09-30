/**
 * 상품 상세 진행형 섹션 바의 활성 섹션·진행률 계산(Track 106-2 · 순수 함수). 기준선(anchor) = 뷰포트 위에서 헤더 + 바 아래까지의 거리(px).
 * 입력 top·height는 getBoundingClientRect 값(뷰포트 기준)이라 DOM 없이 테스트할 수 있다.
 */

export interface SectionBox<Id extends string = string> {
  id: Id
  /** 뷰포트 위 기준 섹션 윗변(px · 위로 지나가면 음수). */
  top: number
  height: number
}

/**
 * 활성 섹션 = 윗변이 기준선에 닿은(지나간) 섹션 중 마지막. 아직 어느 섹션도 닿지 않았으면 null(구매 영역을 보는 중).
 * 페이지 끝에 닿았으면(짧은 마지막 섹션은 기준선까지 올라오지 못한다) 화면 안에 윗변이 들어온 마지막 섹션을 활성으로 본다.
 */
export function resolveActiveSection<Id extends string>(
  boxes: SectionBox<Id>[],
  anchor: number,
  viewportHeight: number,
  atPageEnd: boolean,
): Id | null {
  const ordered = [...boxes].sort((first, second) => first.top - second.top)
  if (atPageEnd) {
    const visible = ordered.filter((box) => box.top < viewportHeight)
    if (visible.length > 0) return visible[visible.length - 1]!.id
  }
  const reached = ordered.filter((box) => box.top <= anchor)
  return reached.length > 0 ? reached[reached.length - 1]!.id : null
}

/**
 * 섹션을 읽은 비율(0~1). 윗변이 기준선에 닿으면 0, 아랫변이 뷰포트 바닥에 닿으면 1 — 기준선 아래 보이는 높이만큼은 "이미 보인" 것으로 친다.
 * 보이는 영역보다 짧은 섹션은 윗변이 기준선에 닿는 순간 다 읽은 것으로 본다.
 */
export function sectionProgress(box: SectionBox, anchor: number, viewportHeight: number): number {
  const scrolled = anchor - box.top
  if (scrolled <= 0) return 0
  const readable = box.height - (viewportHeight - anchor)
  if (readable <= 0) return 1
  return Math.min(1, scrolled / readable)
}
