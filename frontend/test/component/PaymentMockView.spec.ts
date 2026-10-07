import { describe, it, expect } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PaymentMockView from '~/skins/renew/views/PaymentMockView.vue'
import type { PaymentMockPageVm } from '~/skins/contracts/payment-mock'

/**
 * PF-20 모의 결제 실패·취소 안내: 바로구매 출처면 장바구니 언급 없이 상품으로 돌려보내고,
 * 출처가 없으면(장바구니 결제) 기존 장바구니 보관 안내·링크를 그대로 보인다.
 */
function paymentMockVm(buyNowProductPath: string | null): PaymentMockPageVm {
  return reactive<PaymentMockPageVm>({
    hasAttemptKey: true,
    resultMessage: '결제에 실패했습니다.',
    buyNowProductPath,
    methodLabel: '카드',
    amountLabel: '15,000원',
    errorMessage: '',
    submitting: false,
    pay: async () => {},
  })
}

describe('PaymentMockView 실패·취소 안내(PF-20)', () => {
  it('바로구매 출처 → 장바구니 문구 없음 · 상품으로 돌아가기', async () => {
    const wrapper = await mountSuspended(PaymentMockView, { props: { vm: paymentMockVm('/products/prd_01BUY') } })
    expect(wrapper.text()).toContain('결제에 실패했습니다.')
    expect(wrapper.text()).not.toContain('장바구니')
    expect(wrapper.find('a[href="/products/prd_01BUY"]').text()).toBe('상품으로 돌아가기')
  })

  it('출처 없음(장바구니 결제) → 장바구니 보관 안내 · 장바구니로 돌아가기', async () => {
    const wrapper = await mountSuspended(PaymentMockView, { props: { vm: paymentMockVm(null) } })
    expect(wrapper.text()).toContain('장바구니 상품은 그대로 보관되어 있습니다.')
    expect(wrapper.find('a[href="/cart"]').text()).toBe('장바구니로 돌아가기')
  })
})
