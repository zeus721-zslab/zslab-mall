import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EmptyState from '~/components/common/EmptyState.vue'

describe('EmptyState', () => {
  it('props 미전달 시 기본 문구를 렌더한다', async () => {
    const wrapper = await mountSuspended(EmptyState)
    expect(wrapper.text()).toContain('등록된 상품이 없습니다')
  })

  it('message 전달 시 해당 문구를 렌더한다', async () => {
    const wrapper = await mountSuspended(EmptyState, { props: { message: '검색 결과가 없습니다' } })
    expect(wrapper.text()).toContain('검색 결과가 없습니다')
  })
})
