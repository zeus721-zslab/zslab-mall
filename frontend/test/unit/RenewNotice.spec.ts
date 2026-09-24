import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RenewNotice from '~/skins/renew/components/RenewNotice.vue'

/**
 * FE-72 보완 2 renew 공용 알림. 톤별 role(보조기기 읽힘 방식)·아이콘·내용 렌더만 고정한다 — 색은 토큰 클래스라 대상 아님.
 * danger 톤은 화면 촬영 대신 이 테스트로 확인한다.
 */
const CASES = [
  { tone: 'success', role: 'status', icon: 'lucide-circle-check' },
  { tone: 'info', role: 'status', icon: 'lucide-info' },
  { tone: 'warning', role: 'status', icon: 'lucide-triangle-alert' },
  { tone: 'danger', role: 'alert', icon: 'lucide-triangle-alert' },
] as const

describe('RenewNotice — 톤별 role·아이콘', () => {
  for (const { tone, role, icon } of CASES) {
    it(`${tone} → role=${role} · ${icon} 아이콘 · slot 내용`, async () => {
      const wrapper = await mountSuspended(RenewNotice, { props: { tone }, slots: { default: () => '알림 문구' } })
      expect(wrapper.attributes('role')).toBe(role)
      expect(wrapper.attributes('data-tone')).toBe(tone)
      const svg = wrapper.find('svg')
      expect(svg.classes()).toContain(icon)
      expect(svg.attributes('aria-hidden')).toBe('true')
      expect(wrapper.text()).toContain('알림 문구')
    })
  }
})

// FE-79 action 슬롯: 버튼 있음 → ≥768 가운데 정렬·<768 아래 줄 / 없음 → 아이콘을 첫 줄 높이(items-start + mt-0.5)에 맞춤.
describe('RenewNotice — action 슬롯 유무별 정렬', () => {
  it('action 없음 → 루트 items-start · 아이콘 mt-0.5 · 슬롯 영역 없음', async () => {
    const wrapper = await mountSuspended(RenewNotice, { props: { tone: 'info' }, slots: { default: () => '여러 줄 안내' } })
    expect(wrapper.classes()).toContain('items-start')
    expect(wrapper.classes()).not.toContain('md:items-center')
    expect(wrapper.find('svg').classes()).toEqual(expect.arrayContaining(['mt-0.5']))
    expect(wrapper.find('svg').classes()).not.toContain('md:mt-0')
    expect(wrapper.find('[data-slot="notice-action"]').exists()).toBe(false)
  })

  it('action 있음 → 루트 flex-col·md:flex-row·md:items-center · 아이콘 md:mt-0 · 버튼은 슬롯 영역(<768 문구 시작선 들여쓰기)', async () => {
    const wrapper = await mountSuspended(RenewNotice, {
      props: { tone: 'info' },
      slots: { default: () => '안내', action: () => h('button', { type: 'button' }, '확정하러 가기') },
    })
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['flex-col', 'md:flex-row', 'md:items-center']))
    expect(wrapper.classes()).not.toContain('items-start')
    expect(wrapper.find('svg').classes()).toEqual(expect.arrayContaining(['mt-0.5', 'md:mt-0']))
    const action = wrapper.find('[data-slot="notice-action"]')
    expect(action.classes()).toContain('max-md:pl-8')
    expect(action.find('button').text()).toBe('확정하러 가기')
  })
})
