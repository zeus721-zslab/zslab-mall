import { describe, it, expect } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CheckoutView from '~/skins/renew/views/CheckoutView.vue'
import type { CheckoutPageVm } from '~/skins/contracts/checkout'
import { buildCheckoutSummary } from '~/lib/utils/checkout-summary'
import { PAYMENT_METHODS } from '~/lib/constants/payment'
import {
  ADDRESS_DETAIL_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_ROAD_MAX,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
} from '~/lib/constants/account'

/**
 * FE-78 A-2 주문서 주소 안내. 주소가 비면 결제하기가 비활성이라 제출 시 필수 안내가 뜨지 않으므로,
 * 받는 사람·연락처를 채우고 주소만 남았을 때만 주소 영역 아래에 이유를 알린다(canSubmit은 그대로).
 */
function checkoutVm(): CheckoutPageVm {
  return reactive<CheckoutPageVm>({
    cartError: undefined,
    refreshCart: async () => {},
    summary: buildCheckoutSummary([]),
    addressLoadFailed: false,
    hasAddresses: false,
    addressList: [],
    selectedKey: 'new',
    onSelectAddress: () => {},
    recipientName: '',
    recipientPhone: '',
    zonecode: '',
    addressRoad: '',
    addressJibun: '',
    addressDetail: '',
    deliveryMemo: '',
    showSaveCheckbox: true,
    saveAddress: true,
    method: 'CARD',
    errorMessage: '',
    showCartLink: false,
    submitting: false,
    canSubmit: false,
    formatPrice: (value: number) => `${value}원`,
    handleSubmit: async () => {},
    PAYMENT_METHODS,
    RECIPIENT_NAME_MAX,
    RECIPIENT_PHONE_MAX,
    ZONECODE_MAX,
    ADDRESS_ROAD_MAX,
    ADDRESS_JIBUN_MAX,
    ADDRESS_DETAIL_MAX,
  })
}

describe('CheckoutView 주소 안내(FE-78 A-2)', () => {
  it('처음 진입·다른 필수 항목이 비면 미표시 · 주소만 비면 표시 · 주소를 고르면 사라짐', async () => {
    const vm = checkoutVm()
    const wrapper = await mountSuspended(CheckoutView, { props: { vm } })
    const hint = () => wrapper.find('[data-testid="checkout-address-hint"]')
    const HINT = '주소를 검색해 선택해 주세요.'

    expect(hint().attributes('aria-live')).toBe('polite')
    expect(hint().text()).toBe('')

    vm.recipientName = '홍길동'
    await wrapper.vm.$nextTick()
    expect(hint().text()).toBe('')

    vm.recipientPhone = '010-1234-5678'
    await wrapper.vm.$nextTick()
    expect(hint().text()).toBe(HINT)

    vm.zonecode = '06236'
    vm.addressRoad = '서울 강남구 테헤란로 152 (역삼동)'
    await wrapper.vm.$nextTick()
    expect(hint().text()).toBe('')
  })
})
