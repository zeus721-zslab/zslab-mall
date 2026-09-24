import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RenewBadge from '~/skins/renew/components/RenewBadge.vue'

/**
 * FE-76 renew 공용 배지. 톤별 파스텔 bg/ink 쌍·크기 클래스·role 없음·내용 렌더를 고정한다.
 */
const CASES = [
  { tone: 'info', pastel: 'lavender' },
  { tone: 'success', pastel: 'mint' },
  { tone: 'warning', pastel: 'butter' },
  { tone: 'danger', pastel: 'pink' },
  { tone: 'neutral', pastel: 'periwinkle' },
] as const

describe('RenewBadge — 톤별 파스텔 클래스·role 없음', () => {
  for (const { tone, pastel } of CASES) {
    it(`${tone} → ${pastel} bg/ink · 22px 12/600 · role 없음 · slot 내용`, async () => {
      const wrapper = await mountSuspended(RenewBadge, { props: { tone }, slots: { default: () => '배송 중' } })
      const classes = wrapper.classes()
      expect(classes).toContain(`bg-(--pastel-${pastel}-bg)`)
      expect(classes).toContain(`text-(--pastel-${pastel}-ink)`)
      expect(classes).toEqual(expect.arrayContaining(['h-[22px]', 'text-xs', 'font-semibold', 'rounded-full']))
      expect(wrapper.attributes('role')).toBeUndefined()
      expect(wrapper.attributes('data-tone')).toBe(tone)
      expect(wrapper.text()).toBe('배송 중')
    })
  }
})
