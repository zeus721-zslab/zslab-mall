import { describe, it, expect, beforeEach, vi } from 'vitest'

// Track 90-B-3: useSellerToast(관리자 복제)는 의미 색상 4종(danger→sonner error)으로 vue-sonner toast.<variant>에 지속 시간(success·info 3s / warning·danger 5s)을 넘긴다.
const { toastMock } = vi.hoisted(() => ({
  toastMock: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
}))
vi.mock('vue-sonner', () => ({ toast: toastMock }))

import { SELLER_TOAST_DURATION_MS, useSellerToast } from '#layers/seller/app/composables/useSellerToast'

describe('useSellerToast', () => {
  beforeEach(() => {
    Object.values(toastMock).forEach((fn) => fn.mockReset())
  })

  it('타입별 지속 시간: success·info 3000 / warning·danger 5000 · danger는 sonner error variant', () => {
    const toast = useSellerToast()
    toast.success('s')
    toast.info('i')
    toast.warning('w')
    toast.danger('e')
    expect(toastMock.success).toHaveBeenCalledWith('s', { duration: 3000 })
    expect(toastMock.info).toHaveBeenCalledWith('i', { duration: 3000 })
    expect(toastMock.warning).toHaveBeenCalledWith('w', { duration: 5000 })
    expect(toastMock.error).toHaveBeenCalledWith('e', { duration: 5000 })
    expect(SELLER_TOAST_DURATION_MS).toEqual({ success: 3000, info: 3000, warning: 5000, danger: 5000 })
    useSellerToast().show('danger', 'd')
    expect(toastMock.error).toHaveBeenLastCalledWith('d', { duration: 5000 })
  })
})
