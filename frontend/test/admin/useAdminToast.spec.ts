import { describe, it, expect, beforeEach, vi } from 'vitest'

// FE-25 보강: useAdminToast는 의미 색상 4종(danger→sonner error)으로 vue-sonner toast.<variant>에 지속 시간(success·info 3s / warning·danger 5s)과 action을 넘긴다.
const { toastMock } = vi.hoisted(() => ({
  toastMock: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
}))
vi.mock('vue-sonner', () => ({ toast: toastMock }))

import { ADMIN_TOAST_DURATION_MS, useAdminToast } from '#layers/admin/app/composables/useAdminToast'

describe('useAdminToast', () => {
  beforeEach(() => {
    Object.values(toastMock).forEach((fn) => fn.mockReset())
  })

  it('타입별 지속 시간: success·info 3000 / warning·danger 5000 · danger는 sonner error variant', () => {
    const toast = useAdminToast()
    toast.success('s')
    toast.info('i')
    toast.warning('w')
    toast.danger('e')
    expect(toastMock.success).toHaveBeenCalledWith('s', { duration: 3000, action: undefined })
    expect(toastMock.info).toHaveBeenCalledWith('i', { duration: 3000, action: undefined })
    expect(toastMock.warning).toHaveBeenCalledWith('w', { duration: 5000, action: undefined })
    expect(toastMock.error).toHaveBeenCalledWith('e', { duration: 5000, action: undefined })
    expect(ADMIN_TOAST_DURATION_MS).toEqual({ success: 3000, info: 3000, warning: 5000, danger: 5000 })
    useAdminToast().show('danger', 'd')
    expect(toastMock.error).toHaveBeenLastCalledWith('d', { duration: 5000, action: undefined })
  })

  it('action 옵션은 label·onClick으로 전달되고 클릭 시 콜백이 실행된다', () => {
    const onClick = vi.fn()
    useAdminToast().warning('일부 실패', { action: { label: '상세 보기', onClick } })
    const options = toastMock.warning.mock.calls[0]?.[1] as { action: { label: string; onClick: () => void } }
    expect(options.action.label).toBe('상세 보기')
    options.action.onClick()
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
