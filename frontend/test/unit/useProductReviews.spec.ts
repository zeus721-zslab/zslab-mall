import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useProductReviews } from '~/composables/useProductReviews'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ProductDetail } from '~/types/product'
import type { PagedResponse } from '~/types/order'
import type { ReviewItem, ReviewKeywordOption, ReviewSummary } from '~/types/review'

/**
 * Track 106-1 PR2 useProductReviews: 첫 페이지·요약 조회 · 필터 → 첫 페이지 재조회 · 더보기 누적 · 키워드 묶음 막대 ·
 * 옵션 선택지(BE 라벨 형식) · 도움됐어요(비로그인 로그인 이동 · 422 본인 잠금). 네트워크는 구매자 래퍼와 공개 useFetch를 mock한다.
 */
const PRODUCT_ID = 'prd_TEST'

const { apiMock, keywordOptionsMock, setHelpfulMock, navigateToMock, authMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  keywordOptionsMock: { current: [] as ReviewKeywordOption[] },
  setHelpfulMock: vi.fn(),
  navigateToMock: vi.fn(),
  authMock: { isAuthenticated: true, role: 'BUYER', ensureSession: async () => {} },
}))
mockNuxtImport('useBuyerApi', () => () => apiMock)
mockNuxtImport('useReviewKeywordOptions', () => () => ({ data: ref(keywordOptionsMock.current), status: ref('success') }))
mockNuxtImport('useReviewActions', () => () => ({ setHelpful: setHelpfulMock }))
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useAuthStore', () => () => authMock)

function item(reviewId: string): ReviewItem {
  return { reviewId, rating: 5, content: '좋아요', keywords: [], photos: [], helpfulCount: 1, helpedByMe: false, createdAt: '2026-09-20T12:00:00+09:00' }
}

function page(items: ReviewItem[], hasNext: boolean): PagedResponse<ReviewItem> {
  return { items, page: 0, size: 10, totalCount: 30, hasNext }
}

const SUMMARY: ReviewSummary = {
  reviewCount: 4,
  averageRating: 4.5,
  ratingDistribution: [
    { rating: 5, count: 2 },
    { rating: 4, count: 2 },
    { rating: 3, count: 0 },
    { rating: 2, count: 0 },
    { rating: 1, count: 0 },
  ],
  keywords: [
    { code: 'SIZE_FIT', label: '사이즈 딱 맞아요', count: 3 },
    { code: 'QUALITY_GOOD', label: '품질이 좋아요', count: 2 },
    { code: 'SAME_AS_DESCRIPTION', label: '설명과 같아요', count: 1 },
  ],
  recentPhotos: [],
}

function detail(): ProductDetail {
  return {
    productPublicId: PRODUCT_ID,
    name: '셔츠',
    description: null,
    categoryId: 1,
    categoryName: '의류',
    sellerName: '셀러',
    displayPrice: 10000,
    soldOut: false,
    saleStopped: false,
    images: [],
    optionGroups: [],
    variants: [
      { variantPublicId: 'var_1', salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '블랙' }, { groupName: '사이즈', value: 'M' }] },
      { variantPublicId: 'var_2', salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '화이트' }, { groupName: '사이즈', value: 'M' }] },
    ],
    sellerPublicId: 'slr_1',
  }
}

// 같은 key의 useAsyncData는 앱 전체에서 핸들러를 공유하므로, 앞 테스트의 호스트를 해제해야 이 테스트의 필터로 조회한다.
let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

async function mountReviews(): Promise<ProductReviewsVm> {
  let vm: ProductReviewsVm | null = null
  const productDetail = { data: ref(detail()) } as unknown as ReturnType<typeof useProductDetail>
  mounted = await mountSuspended(defineComponent({
    setup() {
      vm = useProductReviews(PRODUCT_ID, productDetail)
      return () => h('div')
    },
  }))
  await flushPromises()
  return vm!
}

/** 목록 경로 호출들의 query. */
function listQueries(): Record<string, unknown>[] {
  const calls = apiMock.mock.calls as unknown as [string, { query?: Record<string, unknown> } | undefined][]
  return calls.filter(([path]) => path === `/v1/products/${PRODUCT_ID}/reviews`).map(([, options]) => options?.query ?? {})
}

describe('useProductReviews', () => {
  beforeEach(() => {
    apiMock.mockReset()
    setHelpfulMock.mockReset()
    navigateToMock.mockReset()
    authMock.isAuthenticated = true
    authMock.role = 'BUYER'
    keywordOptionsMock.current = [
      { code: 'SIZE_FIT', label: '사이즈 딱 맞아요', groupCode: 'PRODUCT', sortOrder: 101 },
      { code: 'QUALITY_GOOD', label: '품질이 좋아요', groupCode: 'QUALITY', sortOrder: 3 },
      { code: 'SAME_AS_DESCRIPTION', label: '설명과 같아요', groupCode: 'QUALITY', sortOrder: 4 },
    ]
    apiMock.mockImplementation(async (path: string, options?: { query?: { page?: number } }) => {
      if (path.endsWith('/summary')) return SUMMARY
      return (options?.query?.page ?? 0) === 0 ? page([item('rvw_a'), item('rvw_b')], true) : page([item('rvw_c')], false)
    })
    clearNuxtData()
  })

  it('첫 페이지(도움순 · size 10) · 분포 막대 · 키워드는 묶음마다 최다 1개(비율 = 고른 수 / 리뷰 수) · 옵션 선택지 = BE 라벨 형식', async () => {
    const reviews = await mountReviews()
    expect(listQueries()).toEqual([{ sort: 'HELPFUL', page: 0, size: 10 }])
    expect(reviews.items.map((entry) => entry.reviewId)).toEqual(['rvw_a', 'rvw_b'])
    expect(reviews.ratingBars.map((bar) => [bar.label, bar.valueText, bar.strong])).toEqual([
      ['5점', '50%', true],
      ['4점', '50%', true],
      ['3점', '0%', false],
      ['2점', '0%', false],
      ['1점', '0%', false],
    ])
    expect(reviews.keywordBars.map((bar) => [bar.label, bar.valueText])).toEqual([
      ['상품 · 사이즈 딱 맞아요', '75%'],
      ['품질 · 품질이 좋아요', '50%'],
    ])
    expect(reviews.optionChoices).toEqual(['색상: 블랙 / 사이즈: M', '색상: 화이트 / 사이즈: M'])
  })

  it('더보기 → 다음 페이지를 이어 붙이고 hasNext 갱신 · 필터 변경 → 첫 페이지부터 다시(누적 초기화)', async () => {
    const reviews = await mountReviews()
    await reviews.loadMore()
    expect(reviews.items.map((entry) => entry.reviewId)).toEqual(['rvw_a', 'rvw_b', 'rvw_c'])
    expect(reviews.hasNext).toBe(false)

    reviews.setPhotoOnly(true)
    reviews.setKeyword('SIZE_FIT')
    await flushPromises()
    expect(listQueries().at(-1)).toEqual({ photoOnly: true, keyword: 'SIZE_FIT', sort: 'HELPFUL', page: 0, size: 10 })
    expect(reviews.items.map((entry) => entry.reviewId)).toEqual(['rvw_a', 'rvw_b'])
    expect(reviews.isFiltered).toBe(true)
  })

  it('도움됐어요: 결과를 항목에 겹쳐 보임 / 비로그인 → 로그인 이동(호출 없음) / 422 → 본인 리뷰로 잠금 + 안내', async () => {
    const reviews = await mountReviews()
    setHelpfulMock.mockResolvedValueOnce({ helped: true, helpfulCount: 2 })
    await reviews.toggleHelpful(reviews.items[0]!)
    expect(setHelpfulMock).toHaveBeenCalledWith('rvw_a', true)
    expect(reviews.items[0]).toMatchObject({ helpedByMe: true, helpfulCount: 2 })

    setHelpfulMock.mockRejectedValueOnce({ statusCode: 422 })
    await reviews.toggleHelpful(reviews.items[1]!)
    expect(reviews.ownReviewIds).toEqual(['rvw_b'])
    expect(reviews.helpfulNotice).toEqual({ reviewId: 'rvw_b', text: '내가 쓴 리뷰에는 누를 수 없어요.' })
    await reviews.toggleHelpful(reviews.items[1]!)
    expect(setHelpfulMock).toHaveBeenCalledTimes(2)

    // writtenByMe(본인 리뷰)면 호출하지 않는다
    await reviews.toggleHelpful({ ...reviews.items[0]!, writtenByMe: true })
    expect(setHelpfulMock).toHaveBeenCalledTimes(2)

    authMock.isAuthenticated = false
    await reviews.toggleHelpful(reviews.items[0]!)
    expect(navigateToMock).toHaveBeenCalledWith(expect.stringContaining('/login?redirect='))
    expect(setHelpfulMock).toHaveBeenCalledTimes(2)
  })
})
