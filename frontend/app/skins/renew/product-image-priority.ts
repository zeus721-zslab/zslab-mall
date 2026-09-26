/**
 * renew 상품 목록 이미지 우선순위(성능 트랙 P2 · FE-85). 쓰는 곳: RenewProductListing(/products·카테고리) · SearchView.
 * 모바일 LCP가 첫 줄 썸네일인데 loading="lazy"라 요청이 늦게 시작됐다(Load Delay 3.2s). 첫 줄만 즉시 불러오고, 첫 장만 높은 우선순위를 준다.
 * 첫 줄 = 1440 기준 5열(목록 그리드 xl:grid-cols-5). 390(2열)에서는 둘째 줄까지 즉시 불러오는 셈이지만 5장이라 받아들인다.
 */
export type ProductImagePriority = 'high' | 'eager'

const FIRST_ROW_COUNT = 5

export function productImagePriority(index: number): ProductImagePriority | undefined {
  if (index === 0) return 'high'
  if (index < FIRST_ROW_COUNT) return 'eager'
  return undefined
}
