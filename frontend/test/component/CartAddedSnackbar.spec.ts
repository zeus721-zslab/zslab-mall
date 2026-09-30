import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, nextTick, reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import CartAddedSnackbar from '~/skins/renew/components/CartAddedSnackbar.vue'

/**
 * FE-99 상세 담기 스낵바: 신호마다 나타나 4초 뒤 사라진다 · 포인터·포커스가 머무는 동안 멈춘다 · 다시 담으면 처음부터 센다.
 * 마운트(Suspense)는 실제 타이머로 하고, 그 뒤 가짜 타이머로 바꿔 숨김 타이머만 조작한다.
 */
const DURATION_MS = 4000
const MESSAGE = '담았어요'

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
  vi.useRealTimers()
})

async function mountSnackbar(): Promise<{ wrapper: VueWrapper; state: { signal: number } }> {
  const state = reactive({ signal: 0 })
  const Host = defineComponent({ setup: () => () => h(CartAddedSnackbar, { signal: state.signal }) })
  const wrapper = await mountSuspended(Host)
  mounted = wrapper
  vi.useFakeTimers()
  return { wrapper, state }
}

async function signalAdded(state: { signal: number }): Promise<void> {
  state.signal += 1
  await nextTick()
}

async function advance(milliseconds: number): Promise<void> {
  vi.advanceTimersByTime(milliseconds)
  await nextTick()
}

describe('CartAddedSnackbar(FE-99)', () => {
  it('신호 전에는 비어 있고, 신호 뒤 4초가 지나면 사라진다 · 장바구니 링크', async () => {
    const { wrapper, state } = await mountSnackbar()
    expect(wrapper.text()).not.toContain(MESSAGE)

    await signalAdded(state)
    expect(wrapper.text()).toContain(MESSAGE)
    expect(wrapper.find('a[href="/cart"]').text()).toBe('장바구니 보기')
    expect(wrapper.find('[aria-live="polite"]').exists()).toBe(true)

    await advance(DURATION_MS - 1)
    expect(wrapper.text()).toContain(MESSAGE)
    await advance(1)
    expect(wrapper.text()).not.toContain(MESSAGE)
  })

  it('포인터·포커스가 머무는 동안 멈추고, 둘 다 떠나면 처음부터 4초를 센다', async () => {
    const { wrapper, state } = await mountSnackbar()
    await signalAdded(state)
    await advance(DURATION_MS - 1000)

    const box = wrapper.find('[aria-live="polite"] div')
    await box.trigger('pointerenter')
    await box.trigger('focusin')
    await advance(DURATION_MS * 3)
    expect(wrapper.text()).toContain(MESSAGE)

    await box.trigger('pointerleave')
    await advance(DURATION_MS * 3)
    expect(wrapper.text()).toContain(MESSAGE)

    await box.trigger('focusout')
    await advance(DURATION_MS - 1)
    expect(wrapper.text()).toContain(MESSAGE)
    await advance(1)
    expect(wrapper.text()).not.toContain(MESSAGE)
  })

  it('보이는 중에 다시 담으면 타이머가 처음부터 다시 시작한다', async () => {
    const { wrapper, state } = await mountSnackbar()
    await signalAdded(state)
    await advance(DURATION_MS - 1000)

    await signalAdded(state)
    await advance(DURATION_MS - 1)
    expect(wrapper.text()).toContain(MESSAGE)
    await advance(1)
    expect(wrapper.text()).not.toContain(MESSAGE)
  })
})
