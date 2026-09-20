import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { nextTick } from 'vue'
import SellerLayout from '#layers/seller/app/layouts/seller.vue'

// 정지(SUSPENDED) 셀러 안내 배너 렌더(외부 검토 R1): markSuspended 호출 검증(useSellerApi.spec)에 더해 레이아웃이 플래그를 실제로 배너로 보여주는지 본다.
// 사이드바·상단바(프로필 API 호출)는 stub·이탈 가드는 무동작 mock. 첫 페인트 게이트(rAF) 통과 후 단언한다.
// 세 번째 케이스(렌더 후 플래그 전환)를 위해 반응형 객체로 둔다.
const { sellerAuthMock } = await vi.hoisted(async () => {
  const { reactive } = await import('vue')
  return { sellerAuthMock: reactive({ suspended: false }) }
})
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
vi.mock('#layers/seller/app/lib/seller-leave-guard', () => ({ useSellerLeaveGuard: () => {} }))
// GET /seller/me 로드(90-B-3)는 useSellerMe.spec이 검증한다 — 여기서는 네트워크 없이 무동작 mock.
vi.mock('#layers/seller/app/composables/useSellerMe', () => ({ useSellerMe: () => ({ me: { value: null }, error: { value: false }, load: vi.fn(), clear: vi.fn() }) }))

const SUSPENDED_NOTICE = '정지 상태의 셀러입니다. 조회는 가능하지만 주문·상품·정산 등 변경 작업은 처리되지 않습니다.'

async function mountLayout() {
  const wrapper = await mountSuspended(SellerLayout, {
    slots: { default: () => '페이지 콘텐츠' },
    global: { plugins: [createVuetify()], stubs: { SellerSidebar: true, SellerTopbar: true, SellerToaster: true } },
  })
  // useSellerFirstPaintGate: 첫 requestAnimationFrame 이후 셸 렌더
  await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
  await nextTick()
  return wrapper
}

describe('seller 레이아웃 — 정지 안내 배너', () => {
  beforeEach(() => {
    sellerAuthMock.suspended = false
  })

  it('suspended=false → 배너 없음·슬롯 렌더', async () => {
    const wrapper = await mountLayout()
    expect(wrapper.text()).toContain('페이지 콘텐츠')
    expect(wrapper.find('[data-testid="seller-suspended-notice"]').exists()).toBe(false)
  })

  it('suspended=true → 배너 표시(role=alert)·정지 문구', async () => {
    sellerAuthMock.suspended = true
    const wrapper = await mountLayout()
    const notice = wrapper.find('[data-testid="seller-suspended-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.attributes('role')).toBe('alert')
    expect(notice.text()).toContain(SUSPENDED_NOTICE)
    expect(wrapper.text()).toContain('페이지 콘텐츠') // 조회는 계속 가능 — 콘텐츠를 가리지 않는다
  })

  it('렌더 후 플래그가 켜지면 배너가 반응형으로 나타난다', async () => {
    const wrapper = await mountLayout()
    expect(wrapper.find('[data-testid="seller-suspended-notice"]').exists()).toBe(false)
    sellerAuthMock.suspended = true
    await nextTick()
    expect(wrapper.find('[data-testid="seller-suspended-notice"]').exists()).toBe(true)
  })
})
