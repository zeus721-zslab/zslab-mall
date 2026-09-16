import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import AdminStatCard from '#layers/admin/app/components/admin/AdminStatCard.vue'

// 통계 카드 렌더(FE-22f). Vuetify 컴포넌트라 테스트 앱에 createVuetify()를 플러그인으로 설치한다(관리자 런타임은 ensureVuetify가 담당).
describe('AdminStatCard', () => {
  it('라벨·수치·보조 문구·그라데이션 아이콘 클래스 렌더', async () => {
    const wrapper = await mountSuspended(AdminStatCard, {
      props: { label: '오늘 주문', value: '128건', caption: '전일 대비 +12%', icon: 'M0 0h24v24H0z', color: 'success' },
      global: { plugins: [createVuetify()] },
    })
    expect(wrapper.text()).toContain('오늘 주문')
    expect(wrapper.find('[data-testid="admin-stat-card-value"]').text()).toBe('128건')
    expect(wrapper.text()).toContain('전일 대비 +12%')
    expect(wrapper.find('.adm-grad-success').exists()).toBe(true)
  })
})
