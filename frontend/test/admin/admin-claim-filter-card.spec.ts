import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminClaimFilterCard from '#layers/admin/app/components/admin/AdminClaimFilterCard.vue'
import { DEFAULT_ADMIN_CLAIM_QUERY } from '#layers/admin/app/lib/admin-claim-query'
import { ADMIN_CLAIM_ACTION_FILTER_OPTIONS } from '#layers/admin/app/lib/constants/admin-claim'

/**
 * 클레임 필터 카드 "필요 액션" select(Track 96-4 FE-56). 옵션 = 후속 처리 전체 + 5종(라벨은 행 버튼 문구) · 선택 → emit('apply', { action }) ·
 * 해제(clear) → action null · URL에서 온 값이 select에 표시된다.
 */
async function mountCard(action: typeof DEFAULT_ADMIN_CLAIM_QUERY.action = null) {
  const wrapper = await mountSuspended(AdminClaimFilterCard, {
    props: { query: { ...DEFAULT_ADMIN_CLAIM_QUERY, action } },
    global: { plugins: [createVuetify()] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

describe('AdminClaimFilterCard — 필요 액션 select(FE-56)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('옵션 6개(후속 처리 전체 + 회수 확인·검수·교환품 발송·배송완료·환불 개시) → 선택 시 apply { action }', async () => {
    const wrapper = await mountCard()
    expect(ADMIN_CLAIM_ACTION_FILTER_OPTIONS.map((option) => option.title))
      .toEqual(['후속 처리 전체', '회수 확인', '검수', '교환품 발송', '배송완료', '환불 개시'])

    const select = wrapper.find('[data-testid="filter-action"]')
    expect(select.exists()).toBe(true)
    await select.find('.v-field__input').trigger('mousedown')
    await select.find('.v-field__input').trigger('click')
    await flushPromises()
    const options = Array.from(document.body.querySelectorAll<HTMLElement>('.v-overlay .v-list-item'))
    expect(options.map((option) => option.textContent?.trim())).toEqual(['후속 처리 전체', '회수 확인', '검수', '교환품 발송', '배송완료', '환불 개시'])
    options[1]!.click()
    await flushPromises()
    expect(wrapper.emitted('apply')?.at(-1)).toEqual([{ action: 'CONFIRM_PICKUP' }])
    wrapper.unmount()
  })

  it('URL에서 온 action이 표시되고 clear 하면 apply { action: null }', async () => {
    const wrapper = await mountCard('FOLLOWUP')
    const select = wrapper.find('[data-testid="filter-action"]')
    expect(select.text()).toContain('후속 처리 전체')
    await select.find('.v-field__clearable .v-icon').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('apply')?.at(-1)).toEqual([{ action: null }])
    wrapper.unmount()
  })
})
