import { describe, it, expect, vi } from 'vitest'
import { computed, nextTick, reactive, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ProductDetailPage from '~/pages/products/[productPublicId].vue'
import type { ProductDetail } from '~/types/product'
import type { ProductReviewsVm } from '~/skins/contracts/product-reviews'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'

/**
 * FE-111(퀄리티 9-3) 섹션 바(D5): 리뷰·질문 첫 페이지 응답 전에는 알약이 "리뷰 0" · "Q&A 0"이 아니라 이름만 보이고,
 * 응답 후에는 건수(0 포함)가 붙는다. 상세 본문은 응답이 끝난 상태로 두고 리뷰·질문 vm만 대기 상태로 바꾼다.
 */
const { routeMock } = vi.hoisted(() => ({
  routeMock: { query: {}, params: { productPublicId: 'prd_TEST' }, meta: {} },
}))
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useProductDetail', () => () => ({
  data: ref<ProductDetail>({
    productPublicId: 'prd_TEST',
    name: '테스트 상품',
    description: null,
    categoryId: 1,
    categoryName: '기타',
    sellerName: '데모 셀러',
    displayPrice: 10000,
    soldOut: false,
    saleStopped: false,
    images: [],
    optionGroups: [],
    variants: [],
    sellerPublicId: 'slr_TEST',
  }),
  pending: ref(false),
  error: ref(null),
  refresh: vi.fn(),
}))
mockNuxtImport('useProductDetailMore', () => () => ({ sellerProducts: computed(() => []) }))
mockNuxtImport('useProductReviews', () => () => reviewsVm())
mockNuxtImport('useProductQuestions', () => () => questionsVm())

let reviews: ProductReviewsVm | null = null
let questions: ProductQuestionsVm | null = null

function reviewsVm(): ProductReviewsVm {
  reviews = reactive({
    summary: null,
    ratingBars: [],
    keywordBars: [],
    recentPhotoThumbnails: [],
    recentPhotoUrls: [],
    items: [],
    totalCount: 0,
    pending: true,
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
  }) as ProductReviewsVm
  return reviews
}

function questionsVm(): ProductQuestionsVm {
  questions = reactive({
    draft: '',
    setDraft: vi.fn(),
    suggestions: [],
    submitting: false,
    submit: vi.fn(),
    notice: null,
    items: [],
    totalCount: 0,
    pending: true,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    actionPendingId: null,
    saveEdit: vi.fn(),
    remove: vi.fn(),
  }) as ProductQuestionsVm
  return questions
}

describe('pages/products/[productPublicId].vue — 섹션 바 건수(D5)', () => {
  it('대기 중 "리뷰" · "Q&A"(0 없음) → 응답 후 "리뷰 0" · "Q&A 0"', async () => {
    const wrapper = await mountSuspended(ProductDetailPage)
    expect(wrapper.get('[data-testid="section-nav-product-reviews"]').text()).toBe('리뷰')
    expect(wrapper.get('[data-testid="section-nav-product-questions"]').text()).toBe('Q&A')

    reviews!.pending = false
    questions!.pending = false
    await nextTick()
    expect(wrapper.get('[data-testid="section-nav-product-reviews"]').text()).toBe('리뷰 0')
    expect(wrapper.get('[data-testid="section-nav-product-questions"]').text()).toBe('Q&A 0')
  })
})
