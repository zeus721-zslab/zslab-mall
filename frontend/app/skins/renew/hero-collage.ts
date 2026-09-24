import type { ProductSummary } from '~/types/product'

/** 메인 히어로 콜라주 한 칸. product가 있으면 상세 링크, imageUrl이 없으면 pastel 블록만 보인다. */
export interface HeroCollageTile {
  key: string
  product: ProductSummary | null
  imageUrl: string | null
  pastel: string
}

// 칸 순서별 대체 파스텔(FE-77). 이미지가 없거나 상품이 모자라면 이 색 블록으로 채운다.
const HERO_COLLAGE_PASTELS = ['var(--pastel-pink-bg)', 'var(--pastel-periwinkle-bg)', 'var(--pastel-mint-bg)']

/**
 * 새로 들어온 상품 앞 3개로 히어로 콜라주 3칸을 만든다(추가 조회 없음). 조회 실패(빈 배열)면 3칸 모두 파스텔이다.
 * 상품은 있지만 대표 이미지가 없는 칸은 파스텔 블록이되 상세 링크는 유지한다.
 */
export function buildHeroCollage(products: ProductSummary[]): HeroCollageTile[] {
  return HERO_COLLAGE_PASTELS.map((pastel, index) => {
    const product = products[index] ?? null
    return {
      key: product?.productPublicId ?? `empty-${index}`,
      product,
      imageUrl: product?.mainImageUrl ?? null,
      pastel,
    }
  })
}
