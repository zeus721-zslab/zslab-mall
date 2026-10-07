import { afterEach, describe, it, expect, vi } from 'vitest'
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

const { routeMock, navigateToMock, productOverride } = vi.hoisted(() => ({
  routeMock: { query: {}, params: { productPublicId: 'prd_TEST' }, meta: {} },
  navigateToMock: vi.fn(),
  // PF-19 케이스만 옵션·variant를 바꿔 끼운다(나머지 케이스는 기본 상품 그대로).
  productOverride: { value: {} as Partial<ProductDetail> },
}))
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('navigateTo', () => navigateToMock)
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
    ...productOverride.value,
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

// P-08: 수량 상한 20(FE-114 · BE 미결제 variant 상한과 같은 값). 초과 입력은 20으로 보정하고 안내한다.
describe('pages/products/[productPublicId].vue — 수량 상한(P-08)', () => {
  it('20에서 + → 20 유지 + 상한 안내 · − 누르면 안내 해제', async () => {
    const wrapper = await mountSuspended(ProductDetailPage)
    const increment = wrapper.find('button[aria-label="수량 증가"]')
    for (let click = 1; click < 20; click += 1) {
      await increment.trigger('click')
    }
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').exists()).toBe(false)

    await increment.trigger('click')
    expect(wrapper.find('button[aria-label="수량 감소"] + span').text()).toBe('20')
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').text()).toBe('수량은 최대 20개까지 선택할 수 있습니다.')

    await wrapper.find('button[aria-label="수량 감소"]').trigger('click')
    expect(wrapper.find('button[aria-label="수량 감소"] + span').text()).toBe('19')
    expect(wrapper.find('[data-testid="quantity-limit-notice"]').exists()).toBe(false)
  })
})

// PF-19: 바로구매는 옵션 유무와 관계없이 본문에 있고, 단일 옵션 상품의 하단 바도 [장바구니 담기][바로구매] 두 버튼이다.
describe('pages/products/[productPublicId].vue — 바로구매 버튼(PF-19)', () => {
  const MOBILE_BAR = 'div.fixed.inset-x-0.bottom-0.z-40'

  afterEach(() => {
    productOverride.value = {}
    navigateToMock.mockReset()
  })

  it('단일 옵션 상품: 본문 바로구매 → 주문서 바로구매 경로 · 하단 바에 담기·바로구매', async () => {
    productOverride.value = { variants: [{ variantPublicId: 'var_SINGLE', salePrice: 10000, soldOut: false, options: [] }] }
    const wrapper = await mountSuspended(ProductDetailPage)
    await wrapper.find('[data-testid="product-buy-now"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/checkout?product=prd_TEST&variant=var_SINGLE&quantity=1')
    const barButtons = wrapper.find(MOBILE_BAR).findAll('button').map((button) => button.text())
    expect(barButtons).toEqual(['장바구니 담기', '바로구매'])
  })

  it('옵션 상품: 본문 바로구매는 옵션 확정 전 비활성 · 확정 후 주문서 바로구매 경로', async () => {
    productOverride.value = {
      optionGroups: [{ name: '색상', displayOrder: 0, values: [{ value: '블랙', displayOrder: 0 }] }],
      variants: [{ variantPublicId: 'var_BLACK', salePrice: 12000, soldOut: false, options: [{ groupName: '색상', value: '블랙' }] }],
    }
    const wrapper = await mountSuspended(ProductDetailPage)
    const buyNow = () => wrapper.find('[data-testid="product-buy-now"]')
    expect(buyNow().attributes('disabled')).toBeDefined()
    await wrapper.find('button[aria-pressed]:not([aria-label])').trigger('click')
    expect(buyNow().attributes('disabled')).toBeUndefined()
    await buyNow().trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/checkout?product=prd_TEST&variant=var_BLACK&quantity=1')
  })

  // FE-114 가드: 상한(20)을 넘겨 눌러도 바로구매 주문서로 넘기는 수량은 20이다.
  it('수량 + 를 상한 넘게 눌러도 바로구매 경로 수량은 20', async () => {
    productOverride.value = { variants: [{ variantPublicId: 'var_SINGLE', salePrice: 10000, soldOut: false, options: [] }] }
    const wrapper = await mountSuspended(ProductDetailPage)
    const increment = wrapper.find('button[aria-label="수량 증가"]')
    for (let click = 0; click < 25; click += 1) {
      await increment.trigger('click')
    }
    await wrapper.find('[data-testid="product-buy-now"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/checkout?product=prd_TEST&variant=var_SINGLE&quantity=20')
  })
})
