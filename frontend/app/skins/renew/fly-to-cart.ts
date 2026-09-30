/**
 * 담기 성공 연출(FE-99): 대표 이미지 복제본이 헤더 장바구니 아이콘으로 날아가고, 도착하면 뱃지가 잠깐 커졌다 돌아온다.
 * 화면 동작만 하고 데이터·이동은 없다. 셸(LayoutShell)과 상세 뷰는 형제 트리라 목적지를 data 속성으로 문서에서 찾는다.
 * element.animate에는 전역 reduced-motion CSS(main.css)가 닿지 않아 여기서 직접 확인하고 연출 전체를 생략한다.
 * 목적지가 없거나 화면 밖이면 생략한다. 대표 이미지가 없으면 뱃지만 튄다.
 */
const CART_TARGET_SELECTOR = '[data-cart-target]'
const CART_BADGE_SELECTOR = '[data-cart-badge]'
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'
// --ease-soft(main.css)와 같은 곡선. element.animate는 CSS 변수를 읽지 못한다.
const EASE_SOFT = 'cubic-bezier(0.2, 0.8, 0.2, 1)'
const FLIGHT_DURATION_MS = 600
// 리뷰 '도움돼요' 튐(ProductReviewCard.vue review-helpful-bump)과 같은 시간·크기.
const BUMP_DURATION_MS = 320
const BUMP_SCALE = 1.3
// 큰 갤러리 이미지를 통째로 날리지 않도록 출발 크기 상한을 둔다.
const FLIGHT_START_MAX_SIZE = 160
// 대표 이미지가 화면 밖이면(하단 바에서 담은 경우) 화면 하단 가운데, 바 위쯤에서 출발한다.
const FALLBACK_START_SIZE = 64
const FALLBACK_START_BOTTOM_GAP = 96
// 도착 크기 = 아이콘 폭의 절반.
const FLIGHT_END_RATIO = 0.5
// 헤더(z-50) 위 · 본문 바로가기(z-60) 아래.
const FLIGHT_Z_INDEX = '55'

interface Box {
  left: number
  top: number
  size: number
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(REDUCED_MOTION_QUERY).matches
}

function isInViewport(rect: DOMRect): boolean {
  return rect.width > 0 && rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth
}

function startBox(image: HTMLImageElement): Box {
  const rect = image.getBoundingClientRect()
  if (!isInViewport(rect)) {
    return {
      left: (window.innerWidth - FALLBACK_START_SIZE) / 2,
      top: window.innerHeight - FALLBACK_START_BOTTOM_GAP - FALLBACK_START_SIZE,
      size: FALLBACK_START_SIZE,
    }
  }
  const size = Math.min(rect.width, rect.height, FLIGHT_START_MAX_SIZE)
  return { left: rect.left + (rect.width - size) / 2, top: rect.top + (rect.height - size) / 2, size }
}

function bump(target: HTMLElement): void {
  const badge = target.querySelector<HTMLElement>(CART_BADGE_SELECTOR) ?? target
  badge.animate(
    [{ transform: 'scale(1)' }, { transform: `scale(${BUMP_SCALE})`, offset: 0.4 }, { transform: 'scale(1)' }],
    { duration: BUMP_DURATION_MS, easing: EASE_SOFT },
  )
}

export function flyToCart(image: HTMLImageElement | null): void {
  if (prefersReducedMotion()) return
  const target = document.querySelector<HTMLElement>(CART_TARGET_SELECTOR)
  // animate 미지원 환경(구형 브라우저·jsdom)은 연출 없이 끝낸다.
  if (!target || typeof target.animate !== 'function') return
  const targetRect = target.getBoundingClientRect()
  if (!isInViewport(targetRect)) return
  if (!image) {
    bump(target)
    return
  }

  const start = startBox(image)
  const endSize = targetRect.width * FLIGHT_END_RATIO
  const scale = endSize / start.size
  const deltaX = targetRect.left + targetRect.width / 2 - (start.left + endSize / 2)
  const deltaY = targetRect.top + targetRect.height / 2 - (start.top + endSize / 2)

  const flyer = document.createElement('img')
  flyer.src = image.currentSrc || image.src
  flyer.alt = ''
  flyer.setAttribute('aria-hidden', 'true')
  Object.assign(flyer.style, {
    position: 'fixed',
    left: `${start.left}px`,
    top: `${start.top}px`,
    width: `${start.size}px`,
    height: `${start.size}px`,
    objectFit: 'cover',
    borderRadius: '16px',
    transformOrigin: 'top left',
    pointerEvents: 'none',
    zIndex: FLIGHT_Z_INDEX,
  })
  document.body.append(flyer)

  const flight = flyer.animate(
    [
      { transform: 'translate(0, 0) scale(1)', opacity: 1 },
      { transform: `translate(${deltaX}px, ${deltaY}px) scale(${scale})`, opacity: 0.6 },
    ],
    { duration: FLIGHT_DURATION_MS, easing: EASE_SOFT },
  )
  flight.onfinish = () => {
    flyer.remove()
    bump(target)
  }
  flight.oncancel = () => flyer.remove()
}
