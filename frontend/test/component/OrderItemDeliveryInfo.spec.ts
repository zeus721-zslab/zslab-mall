import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ItemDeliveryInfo from '~/components/order/ItemDeliveryInfo.vue'
import type { OrderItemDelivery } from '~/types/order'

// Track 96-2(FE-54·C-05): 품목 배송 정보 블록 분기·송장 복사. 외부 추적 링크는 없어야 한다(결정 범위 외).

const DELIVERED: OrderItemDelivery = {
  carrier: 'HANJIN', trackingNo: '1234-5678', status: 'DELIVERED', shippedAt: '2026-09-10T09:00:00+09:00', deliveredAt: '2026-09-12T15:30:00+09:00',
}

async function mount(delivery: OrderItemDelivery | null | undefined, itemStatusCode: string) {
  return mountSuspended(ItemDeliveryInfo, { props: { delivery, itemStatusCode } })
}

describe('components/order/ItemDeliveryInfo.vue', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('배송 정보 있음 → 택배사 라벨·송장번호·발송일·배송완료일·복사 버튼, 외부 링크 없음', async () => {
    const wrapper = await mount(DELIVERED, 'DELIVERED')
    expect(wrapper.find('[data-testid="item-delivery-carrier"]').text()).toBe('한진택배')
    expect(wrapper.find('[data-testid="item-delivery-tracking-no"]').text()).toBe('1234-5678')
    expect(wrapper.find('[data-testid="item-delivery-shipped-at"]').text()).toBe('발송일 2026.09.10 09:00')
    expect(wrapper.find('[data-testid="item-delivery-delivered-at"]').text()).toBe('배송완료일 2026.09.12 15:30')
    expect(wrapper.find('[data-testid="item-delivery-copy"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="item-delivery-preparing"]').exists()).toBe(false)
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('배송중(배송완료일 null) → 배송완료일 행 미렌더', async () => {
    const wrapper = await mount({ ...DELIVERED, status: 'SHIPPING', deliveredAt: null }, 'SHIPPING')
    expect(wrapper.find('[data-testid="item-delivery-shipped-at"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="item-delivery-delivered-at"]').exists()).toBe(false)
  })

  it('배송 정보 없음 + 발송 전 상태(PAID·PREPARING) → "발송 준비 중"', async () => {
    for (const code of ['PAID', 'PREPARING']) {
      const wrapper = await mount(undefined, code)
      expect(wrapper.find('[data-testid="item-delivery"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="item-delivery-preparing"]').text()).toBe('발송 준비 중')
    }
  })

  it('배송 정보 없음 + 발송 전이 아닌 상태(ORDERED·CANCELLED·CONFIRMED) → 아무것도 렌더하지 않음', async () => {
    for (const code of ['ORDERED', 'CANCELLED', 'CONFIRMED']) {
      const wrapper = await mount(null, code)
      expect(wrapper.find('[data-testid="item-delivery"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="item-delivery-preparing"]').exists()).toBe(false)
    }
  })

  it('복사 성공 → clipboard.writeText(송장번호)·인라인 성공 안내', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const wrapper = await mount(DELIVERED, 'DELIVERED')
    await wrapper.find('[data-testid="item-delivery-copy"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('1234-5678')
    expect(wrapper.find('[data-testid="item-delivery-copy-notice"]').text()).toBe('송장번호를 복사했습니다.')
    vi.unstubAllGlobals()
  })

  it('복사 실패(권한 거부) → 인라인 실패 안내·console.warn', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = await mount(DELIVERED, 'DELIVERED')
    await wrapper.find('[data-testid="item-delivery-copy"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="item-delivery-copy-notice"]').text()).toContain('복사하지 못했습니다')
    expect(warn).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
