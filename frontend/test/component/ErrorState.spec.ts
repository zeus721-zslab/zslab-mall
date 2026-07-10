import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ErrorState from '~/components/common/ErrorState.vue'

describe('ErrorState', () => {
  it('props 미전달 시 기본 문구를 렌더한다', async () => {
    const wrapper = await mountSuspended(ErrorState)
    expect(wrapper.text()).toContain('상품을 불러오지 못했습니다')
  })

  it('message 전달 시 해당 문구를 렌더한다', async () => {
    const wrapper = await mountSuspended(ErrorState, { props: { message: '주문을 불러오지 못했습니다' } })
    expect(wrapper.text()).toContain('주문을 불러오지 못했습니다')
  })

  it('재시도 버튼 클릭 시 retry를 emit한다', async () => {
    const wrapper = await mountSuspended(ErrorState)
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })
})
