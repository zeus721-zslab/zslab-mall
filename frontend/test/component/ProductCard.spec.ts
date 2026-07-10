import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ProductCard from '~/components/ProductCard.vue'
import type { ProductSummary } from '~/types/product'

// 게이트용 유효 fixture(mainImageUrl 있음·soldOut false 기본).
const product: ProductSummary = {
  productPublicId: 'prd_gate_0001',
  name: '게이트 테스트 상품',
  mainImageUrl: 'https://example.com/image.jpg',
  displayPrice: 19900,
  soldOut: false,
  categoryId: 1,
  categoryName: '의류',
  sellerName: '게이트샵',
}

// STEP2-0 게이트: @vue/test-utils mount는 NuxtLink를 <routerlink> stub으로만 렌더(실 <a href> 미해석)해
// 링크 시맨틱 검증이 불가했다. @nuxt/test-utils/runtime의 mountSuspended는 Nuxt 앱 컨텍스트(router 포함)를
// 세워 NuxtLink를 실제 앵커로 해석한다. mock·stub 없이 auto-import(NuxtLink·computed)가 해석되는지 실측한다.
describe('ProductCard (STEP2-0 게이트)', () => {
  it('product.name 텍스트를 렌더한다', async () => {
    const wrapper = await mountSuspended(ProductCard, { props: { product } })
    expect(wrapper.text()).toContain('게이트 테스트 상품')
  })

  it('NuxtLink가 상세 경로를 anchor href로 노출한다', async () => {
    const wrapper = await mountSuspended(ProductCard, { props: { product } })
    // NuxtLink가 실제 <a> 앵커로 해석되고 href에 상세 경로가 담기는지(링크 이동 아님·속성까지만).
    const anchor = wrapper.find('a')
    expect(anchor.exists()).toBe(true)
    expect(anchor.attributes('href')).toContain(`/products/${product.productPublicId}`)
  })

  it('가격을 천단위 포맷 + 원으로 표시한다', async () => {
    const wrapper = await mountSuspended(ProductCard, { props: { product } })
    const text = wrapper.text()
    // toLocaleString 전체 비교 대신 견고하게: 숫자 존재 + 원 포함.
    expect(text).toContain('원')
    expect(text).toMatch(/19[,.]?900/)
  })
})
