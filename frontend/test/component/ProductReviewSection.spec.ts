import { describe, it, expect, vi, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import ProductReviewSection from '~/skins/renew/components/ProductReviewSection.vue'
import RenewProductCard from '~/skins/renew/components/RenewProductCard.vue'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ReviewItem, ReviewSummary } from '~/types/review'
import type { ProductSummary } from '~/types/product'

/**
 * Track 106-1 PR2 상품 상세 리뷰 섹션 · 목록 카드 별점. BE는 리뷰 0건이면 averageRating·summaryText 키를 뺀다(NON_NULL) —
 * 그때 평균·요약 블록이 그려지지 않는지, 있을 때 머리·분포·말풍선·필터·카드·더보기가 vm대로 나오는지 본다.
 */

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function review(overrides: Partial<ReviewItem> = {}): ReviewItem {
  return {
    reviewId: 'rvw_1',
    rating: 5,
    content: '핏이 좋아요',
    keywords: [{ code: 'QUALITY_GOOD', label: '품질이 좋아요' }],
    photos: [{ url: '/p/1.png', thumbnailUrl: '/p/1_thumb.png' }],
    helpfulCount: 2,
    createdAt: '2026-09-20T12:00:00.000+09:00',
    ...overrides,
  }
}

function vmWith(overrides: Partial<ProductReviewsVm>): ProductReviewsVm {
  return reactive({
    summary: null,
    ratingBars: [],
    keywordBars: [],
    recentPhotoThumbnails: [],
    recentPhotoUrls: [],
    items: [],
    totalCount: 0,
    pending: false,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    filter: { photoOnly: false, keyword: null, option: null, sort: 'HELPFUL' },
    isFiltered: false,
    optionChoices: [],
    setPhotoOnly: vi.fn(),
    setKeyword: vi.fn(),
    setOption: vi.fn(),
    setSort: vi.fn(),
    resetFilter: vi.fn(),
    toggleHelpful: vi.fn(),
    helpfulPendingId: null,
    ownReviewIds: [],
    helpfulNotice: null,
    ...overrides,
  }) as ProductReviewsVm
}

const EMPTY_SUMMARY: ReviewSummary = {
  reviewCount: 0,
  ratingDistribution: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: 0 })),
  keywords: [],
  recentPhotos: [],
}

describe('ProductReviewSection', () => {
  it('0건(averageRating·summaryText 키 없음) → "리뷰 0" · 평균·요약 없음 · 빈 상태 문구', async () => {
    const wrapper = await mountSuspended(ProductReviewSection, { props: { reviews: vmWith({ summary: EMPTY_SUMMARY }) } })
    expect(wrapper.get('[data-testid="product-reviews-count"]').text()).toBe('0')
    expect(wrapper.find('[data-testid="product-reviews-average"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="product-reviews-summary"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="product-reviews-empty"]').text()).toContain('첫 리뷰를 기다리고 있어요')
  })

  it('요약 있음 → 머리 평균 · 분포 막대 · 키워드 막대 · 말풍선 · 사진 띠 · 카드 · 더보기', async () => {
    const summary: ReviewSummary = {
      reviewCount: 3,
      averageRating: 4.3,
      ratingDistribution: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: rating === 5 ? 2 : rating === 3 ? 1 : 0 })),
      keywords: [{ code: 'QUALITY_GOOD', label: '품질이 좋아요', count: 2 }],
      recentPhotos: [{ reviewId: 'rvw_1', url: '/p/1.png', thumbnailUrl: '/p/1_thumb.png' }],
      summaryText: '평균 4.3점 · 품질이 좋아요',
    }
    const reviews = vmWith({
      summary,
      ratingBars: [{ key: '5', label: '5점', ratio: 2 / 3, valueText: '67%', strong: true }],
      keywordBars: [{ key: 'QUALITY_GOOD', label: '품질 · 품질이 좋아요', ratio: 2 / 3, valueText: '67%', strong: false }],
      recentPhotoThumbnails: ['/p/1_thumb.png'],
      recentPhotoUrls: ['/p/1.png'],
      items: [review(), review({ reviewId: 'rvw_2', photos: [], keywords: [] })],
      totalCount: 3,
      hasNext: true,
    })
    const wrapper = await mountSuspended(ProductReviewSection, { props: { reviews } })
    expect(wrapper.get('[data-testid="product-reviews-count"]').text()).toBe('3')
    expect(wrapper.get('[data-testid="product-reviews-average"]').text()).toContain('4.3')
    expect(wrapper.findAll('[data-testid="product-reviews-summary"] [data-testid="rating-bar"]')).toHaveLength(2)
    expect(wrapper.get('[data-testid="product-reviews-summary-text"]').text()).toContain('평균 4.3점 · 품질이 좋아요')
    expect(wrapper.findAll('[data-testid="photo-strip-item"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="review-card"]')).toHaveLength(2)

    await wrapper.get('[data-testid="more-button"]').trigger('click')
    expect(reviews.loadMore).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="review-filter-photo"]').trigger('click')
    expect(reviews.setPhotoOnly).toHaveBeenCalledWith(true)
    await wrapper.get('[data-testid="review-filter-keyword-QUALITY_GOOD"]').trigger('click')
    expect(reviews.setKeyword).toHaveBeenCalledWith('QUALITY_GOOD')
    await wrapper.get('[data-testid="review-sort-LATEST"]').trigger('click')
    expect(reviews.setSort).toHaveBeenCalledWith('LATEST')
  })

  it('요약 조회 실패 + 필터 중 0건 → 빈 상태 대신 필터 바 유지 · "조건에 맞는 리뷰가 없어요" · 전체로 되돌리기', async () => {
    const reviews = vmWith({ summary: null, items: [], totalCount: 0, isFiltered: true, filter: { photoOnly: true, keyword: null, option: null, sort: 'HELPFUL' } })
    const wrapper = await mountSuspended(ProductReviewSection, { props: { reviews } })
    expect(wrapper.find('[data-testid="product-reviews-empty"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="product-reviews-filter-empty"]').text()).toBe('조건에 맞는 리뷰가 없어요')
    await wrapper.get('[data-testid="review-filter-all"]').trigger('click')
    expect(reviews.resetFilter).toHaveBeenCalledTimes(1)
  })

  it('summaryText 없음 → 말풍선 생략 · 옵션 선택지 없으면 옵션 필터 없음', async () => {
    const summary: ReviewSummary = { ...EMPTY_SUMMARY, reviewCount: 1, averageRating: 5 }
    const wrapper = await mountSuspended(ProductReviewSection, { props: { reviews: vmWith({ summary, items: [review()], totalCount: 1 }) } })
    expect(wrapper.find('[data-testid="product-reviews-summary-text"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="review-filter-option"]').exists()).toBe(false)
  })

  it('카드: 도움됐어요 aria-pressed(helpedByMe) · writtenByMe면 처음부터 잠김 · 422로 판정된 본인 리뷰도 잠김 · 누르면 toggleHelpful', async () => {
    const summary: ReviewSummary = { ...EMPTY_SUMMARY, reviewCount: 3, averageRating: 4 }
    const reviews = vmWith({
      summary,
      items: [
        review({ reviewId: 'rvw_mine', helpedByMe: false }),
        review({ reviewId: 'rvw_other', helpedByMe: true, writtenByMe: false }),
        review({ reviewId: 'rvw_written', helpedByMe: false, writtenByMe: true }),
      ],
      totalCount: 3,
      ownReviewIds: ['rvw_mine'],
      helpfulNotice: { reviewId: 'rvw_mine', text: '내가 쓴 리뷰에는 누를 수 없어요.' },
    })
    const wrapper = await mountSuspended(ProductReviewSection, { props: { reviews } })
    const buttons = wrapper.findAll('[data-testid="review-card-helpful"]')
    expect((buttons[0]!.element as HTMLButtonElement).disabled).toBe(true)
    expect((buttons[1]!.element as HTMLButtonElement).disabled).toBe(false)
    expect((buttons[2]!.element as HTMLButtonElement).disabled).toBe(true)
    expect(buttons[1]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="review-card-helpful-notice"]').text()).toBe('내가 쓴 리뷰에는 누를 수 없어요.')
    await buttons[1]!.trigger('click')
    expect(reviews.toggleHelpful).toHaveBeenCalledWith(expect.objectContaining({ reviewId: 'rvw_other' }))
  })

  it('카드 사진 → 라이트박스(그 리뷰 원본 사진)', async () => {
    const summary: ReviewSummary = { ...EMPTY_SUMMARY, reviewCount: 1, averageRating: 5 }
    mounted = await mountSuspended(ProductReviewSection, {
      props: { reviews: vmWith({ summary, items: [review()], totalCount: 1 }) },
      attachTo: document.body,
    })
    await mounted.get('[data-testid="review-card-photo"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('[data-testid="lightbox-image"]')?.getAttribute('src')).toBe('/p/1.png')
  })
})

describe('RenewProductCard — 별점 줄', () => {
  const PRODUCT: ProductSummary = {
    productPublicId: 'prd_1',
    name: '상품',
    mainImageUrl: null,
    displayPrice: 10000,
    soldOut: false,
    categoryId: 1,
    categoryName: '기타',
    sellerName: '셀러',
    sellerPublicId: 'slr_1',
    reviewCount: 0,
  }

  it('리뷰 있음 → 가격 아래 "★평균 (N)"', async () => {
    const wrapper = await mountSuspended(RenewProductCard, { props: { product: { ...PRODUCT, averageRating: 4.5, reviewCount: 1234 } } })
    const rating = wrapper.get('[data-testid="product-card-rating"]')
    expect(rating.text()).toContain('4.5')
    expect(rating.text()).toContain('1,234')
  })

  it('reviewCount 0(averageRating 키 없음) → 줄 숨김', async () => {
    const wrapper = await mountSuspended(RenewProductCard, { props: { product: PRODUCT } })
    expect(wrapper.find('[data-testid="product-card-rating"]').exists()).toBe(false)
  })
})
