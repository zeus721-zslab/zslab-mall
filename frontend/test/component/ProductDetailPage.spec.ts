import { describe, it, expect, vi } from 'vitest'
import { computed, ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ProductDetailPage from '~/pages/products/[productPublicId].vue'
import type { ProductDetail } from '~/types/product'

/**
 * FE-85 상세 대표 이미지. 사용자가 고른 썸네일이 없으면 대표(main 우선) 이미지가 렌더 시점에 바로 나오고,
 * LCP 요소라 loading="eager"·fetchpriority="high"다. (SSR HTML 포함 여부는 로컬 SSR 실측으로 확인 — 테스트 환경은 클라이언트 마운트)
 */
const MAIN_IMAGE_URL = '/api/v1/files/products/main.png'
const SUB_IMAGE_URL = '/api/v1/files/products/sub.png'

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
    // displayOrder가 앞서도 main이 대표다.
    images: [
      { imageUrl: SUB_IMAGE_URL, displayOrder: 0, main: false },
      { imageUrl: MAIN_IMAGE_URL, displayOrder: 1, main: true },
    ],
    optionGroups: [],
    variants: [],
    sellerPublicId: 'slr_TEST',
  }),
  pending: ref(false),
  error: ref(null),
  refresh: vi.fn(),
}))
mockNuxtImport('useProductDetailMore', () => () => ({ sellerProducts: computed(() => []) }))
// 리뷰 섹션(Track 106-1)은 ProductReviewSection 테스트가 따로 본다 — 여기서는 조회하지 않게 비운다.
mockNuxtImport('useProductReviews', () => () => null)
// 묻기 섹션(Track 106-2)은 ProductQuestionSection 테스트가 따로 본다 — 여기서는 조회하지 않게 비운다.
mockNuxtImport('useProductQuestions', () => () => null)

describe('pages/products/[productPublicId].vue — 대표 이미지', () => {
  it('마운트 직후 대표 이미지 · eager · fetchpriority high → 썸네일 클릭으로 교체', async () => {
    const wrapper = await mountSuspended(ProductDetailPage)
    const hero = wrapper.find(`img[src="${MAIN_IMAGE_URL}"][alt="테스트 상품"]`)
    expect(hero.exists()).toBe(true)
    expect(hero.attributes('loading')).toBe('eager')
    expect(hero.attributes('fetchpriority')).toBe('high')

    await wrapper.find('button[aria-label="테스트 상품 이미지 2"]').trigger('click')
    expect(wrapper.find('img[fetchpriority="high"]').attributes('src')).toBe(SUB_IMAGE_URL)
  })
})

// P-08: 수량 상한 999(BE CartItem.MAX_QUANTITY). 초과 입력은 999로 보정하고 안내한다.
describe('pages/products/[productPublicId].vue — 수량 상한(P-08)', () => {
  it('999에서 + → 999 유지 + 상한 안내 · − 누르면 안내 해제', async () => {
    const wrapper = await mountSuspended(ProductDetailPage)
    const increment = wrapper.find('button[aria-label="수량 증가"]')
    for (let click = 1; click < 999; click += 1) {
      await increment.trigger('click')
    }
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').exists()).toBe(false)

    await increment.trigger('click')
    expect(wrapper.find('button[aria-label="수량 감소"] + span').text()).toBe('999')
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').text()).toBe('수량은 최대 999개까지 선택할 수 있습니다.')

    await wrapper.find('button[aria-label="수량 감소"]').trigger('click')
    expect(wrapper.find('button[aria-label="수량 감소"] + span').text()).toBe('998')
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').exists()).toBe(false)
  })
})
