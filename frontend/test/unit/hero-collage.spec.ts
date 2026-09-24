import { describe, it, expect } from 'vitest'
import { buildHeroCollage } from '~/skins/renew/hero-collage'
import type { ProductSummary } from '~/types/product'

function product(productPublicId: string, mainImageUrl: string | null): ProductSummary {
  return {
    productPublicId,
    name: `상품 ${productPublicId}`,
    mainImageUrl,
    displayPrice: 10000,
    soldOut: false,
    categoryId: 1,
    categoryName: '의류',
    sellerName: '셀러',
    sellerPublicId: 'slr_1',
  }
}

// FE-77 히어로 콜라주: 앞 3개만 · 이미지 없는 상품 칸 = 파스텔 + 링크 유지 · 모자란 칸 = 파스텔(링크 없음) · 칸 순서 pink·periwinkle·mint.
describe('buildHeroCollage', () => {
  it('앞 3개만 쓰고 이미지 없음·개수 부족·조회 실패(빈 배열)는 칸 순서 파스텔로 채운다', () => {
    const pastels = ['var(--pastel-pink-bg)', 'var(--pastel-periwinkle-bg)', 'var(--pastel-mint-bg)']

    const full = buildHeroCollage([product('p1', '/a.jpg'), product('p2', '/b.jpg'), product('p3', '/c.jpg'), product('p4', '/d.jpg')])
    expect(full.map((tile) => tile.product?.productPublicId)).toEqual(['p1', 'p2', 'p3'])
    expect(full.map((tile) => tile.imageUrl)).toEqual(['/a.jpg', '/b.jpg', '/c.jpg'])

    const partial = buildHeroCollage([product('p1', null), product('p2', '/b.jpg')])
    expect(partial.map((tile) => tile.product?.productPublicId ?? null)).toEqual(['p1', 'p2', null])
    expect(partial.map((tile) => tile.imageUrl)).toEqual([null, '/b.jpg', null])
    expect(partial.map((tile) => tile.pastel)).toEqual(pastels)

    const failed = buildHeroCollage([])
    expect(failed.map((tile) => [tile.product, tile.imageUrl])).toEqual([[null, null], [null, null], [null, null]])
    expect(new Set(failed.map((tile) => tile.key)).size).toBe(3)
  })
})
