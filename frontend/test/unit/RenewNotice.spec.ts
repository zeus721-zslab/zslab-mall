import { describe, it, expect } from 'vitest'
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
