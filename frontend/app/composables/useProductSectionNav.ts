import {
  PRODUCT_QUESTION_INPUT_ID,
  PRODUCT_SECTION_IDS,
  PRODUCT_SECTION_NAV_ID,
  PRODUCT_SECTION_OFFSET_VAR,
  PRODUCT_SECTION_SCROLL_GAP,
  type ProductSectionId,
} from '~/lib/constants/product-sections'
import { resolveActiveSection, sectionProgress, type SectionBox } from '~/lib/utils/section-nav'
import type { ProductSectionNavVm } from '~/skins/contracts/product-section-nav'

const SECTION_ORDER: ProductSectionId[] = [PRODUCT_SECTION_IDS.description, PRODUCT_SECTION_IDS.reviews, PRODUCT_SECTION_IDS.questions]
/** 문서 끝 판정 여유(px · 소수점 스크롤 위치 오차). */
const PAGE_END_TOLERANCE = 2
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

/**
 * 상품 상세 진행형 섹션 바·한눈에 칩(Track 106-2 · 페이지 수준). 섹션은 id(PRODUCT_SECTION_IDS)로 찾고, 스크롤·크기 변화 때 한 프레임에 한 번
 * 활성 섹션과 섹션별 읽은 비율을 다시 계산한다(section-nav 순수 함수). IntersectionObserver는 섹션이 뷰포트보다 길면 중간을 지나는 동안
 * 콜백이 오지 않아 진행률을 구할 수 없으므로 passive scroll + requestAnimationFrame을 쓴다.
 * 헤더·바 높이를 재어 바가 붙을 위치(stickyTop)와 섹션 스크롤 여백(CSS 변수 · scroll-margin-top)을 맞춘다. 서버 렌더에서는 아무것도 하지 않는다.
 */
export function useProductSectionNav(): ProductSectionNavVm {
  const activeId = ref<ProductSectionId | null>(null)
  const progress = ref<Partial<Record<ProductSectionId, number>>>({})
  const stickyTop = ref(0)

  let frame = 0
  let resizeObserver: ResizeObserver | null = null

  function measureOffsets(): number {
    const header = document.querySelector('header')
    const headerHeight = header ? header.getBoundingClientRect().height : 0
    const barHeight = document.getElementById(PRODUCT_SECTION_NAV_ID)?.getBoundingClientRect().height ?? 0
    stickyTop.value = headerHeight
    const offset = headerHeight + barHeight + PRODUCT_SECTION_SCROLL_GAP
    document.documentElement.style.setProperty(PRODUCT_SECTION_OFFSET_VAR, `${offset}px`)
    return offset
  }

  function update(): void {
    frame = 0
    const anchor = measureOffsets()
    const viewportHeight = window.innerHeight
    const boxes: SectionBox<ProductSectionId>[] = SECTION_ORDER.flatMap((id) => {
      const element = document.getElementById(id)
      if (!element) return []
      const rect = element.getBoundingClientRect()
      return [{ id, top: rect.top, height: rect.height }]
    })
    const atPageEnd = window.scrollY + viewportHeight >= document.documentElement.scrollHeight - PAGE_END_TOLERANCE
    activeId.value = resolveActiveSection(boxes, anchor, viewportHeight, atPageEnd)
    progress.value = Object.fromEntries(boxes.map((box) => [box.id, sectionProgress(box, anchor, viewportHeight)]))
  }

  function scheduleUpdate(): void {
    if (frame === 0) frame = requestAnimationFrame(update)
  }

  function prefersReducedMotion(): boolean {
    return typeof window.matchMedia === 'function' && window.matchMedia(REDUCED_MOTION_QUERY).matches
  }

  function go(id: ProductSectionId): void {
    document.getElementById(id)?.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
  }

  function ask(): void {
    go(PRODUCT_SECTION_IDS.questions)
    // 포커스가 스크롤을 다시 일으키면 부드러운 이동이 끊기므로 preventScroll로 준다.
    document.getElementById(PRODUCT_QUESTION_INPUT_ID)?.focus({ preventScroll: true })
  }

  onMounted(() => {
    window.addEventListener('scroll', scheduleUpdate, { passive: true })
    window.addEventListener('resize', scheduleUpdate, { passive: true })
    // 섹션 내용(리뷰·질문 목록)이 늦게 채워져 높이가 바뀌어도 다시 잰다(ResizeObserver가 없는 환경은 scroll·resize만).
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(scheduleUpdate)
      resizeObserver.observe(document.body)
    }
    scheduleUpdate()
  })

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', scheduleUpdate)
    window.removeEventListener('resize', scheduleUpdate)
    resizeObserver?.disconnect()
    if (frame !== 0) cancelAnimationFrame(frame)
    document.documentElement.style.removeProperty(PRODUCT_SECTION_OFFSET_VAR)
  })

  return reactive({ activeId, progress, stickyTop, go, ask })
}
