import { describe, it, expect, vi, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import AddressesView from '~/skins/renew/views/AddressesView.vue'
import type { AddressesPageVm } from '~/skins/contracts/addresses'
import type { Address } from '~/types/address'
import {
  ADDRESS_DETAIL_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_LABEL_MAX,
  ADDRESS_ROAD_MAX,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
} from '~/lib/constants/account'

/**
 * FE-79 배송지 관리: 기본 배송지 띠 면 + 나머지 흰 카드 · 연락처 표시 형식 · 주소 비움 저장 안내(요청 없음) ·
 * 폼 모달 닫힘 뒤 초기화(뷰가 resetForm 호출) · 삭제 확인 pending. 모달은 reka Portal(document.body)이라 document 기준으로 조회한다.
 */
const ADDRESSES: Address[] = [
  { id: 1, isDefault: true, addressLabel: '집', recipientName: '홍길동', recipientPhone: '+821012345678', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 152', addressDetail: '101호' },
  { id: 2, isDefault: false, recipientName: '김철수', recipientPhone: '0212345678', zonecode: '04524', addressRoad: '서울 중구 세종대로 1' },
]

function addressesVm(): AddressesPageVm {
  const vm: AddressesPageVm = reactive<AddressesPageVm>({
    pending: false,
    error: undefined,
    data: ADDRESSES,
    refresh: async () => {},
    editingId: null,
    form: { addressLabel: '', recipientName: '', recipientPhone: '', zonecode: '', addressRoad: '', addressJibun: '', addressDetail: '', isDefault: false },
    submitting: false,
    errorMessage: '',
    successMessage: '',
    resetForm: vi.fn(),
    startEdit: vi.fn(),
    handleSubmit: vi.fn(async () => {}),
    handleSetDefault: vi.fn(async () => {}),
    handleRemove: vi.fn(async () => {}),
    formOpen: false,
    openCreate: () => {
      vm.formOpen = true
    },
    closeForm: () => {
      vm.formOpen = false
    },
    removeTargetId: null,
    removing: false,
    requestRemove: (addressId: number) => {
      vm.removeTargetId = addressId
    },
    cancelRemove: vi.fn(),
    confirmRemove: vi.fn(async () => {}),
    RECIPIENT_NAME_MAX,
    RECIPIENT_PHONE_MAX,
    ADDRESS_LABEL_MAX,
    ZONECODE_MAX,
    ADDRESS_ROAD_MAX,
    ADDRESS_JIBUN_MAX,
    ADDRESS_DETAIL_MAX,
  })
  return vm
}

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function byTestId(testId: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
}

describe('AddressesView(FE-79)', () => {
  it('기본 배송지 = 라벤더 띠 면(기본으로 설정 없음) · 나머지 = 흰 카드 + 기본으로 설정 · 연락처 표시 형식', async () => {
    mounted = await mountSuspended(AddressesView, { props: { vm: addressesVm() } })
    const cards = mounted.findAll('[data-testid="address-card"]')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.classes()).toContain('bg-(--pastel-lavender-bg)')
    expect(cards[0]!.text()).toContain('010-1234-5678')
    expect(cards[0]!.text()).not.toContain('기본으로 설정')
    expect(cards[1]!.classes()).toEqual(expect.arrayContaining(['bg-white', 'shadow-e1']))
    expect(cards[1]!.text()).toContain('02-1234-5678')
    expect(cards[1]!.text()).toContain('기본으로 설정')
  })

  it('주소 비움 저장 → 요청 없이 안내(aria-live) · 주소를 채우면 저장 요청', async () => {
    const vm = addressesVm()
    mounted = await mountSuspended(AddressesView, { props: { vm }, attachTo: document.body })
    vm.openCreate()
    await flushPromises()
    const hint = byTestId('address-form-address-hint')!
    expect(hint.getAttribute('aria-live')).toBe('polite')
    expect(hint.textContent?.trim()).toBe('')

    vm.form.recipientName = '홍길동'
    vm.form.recipientPhone = '010-1234-5678'
    byTestId('address-form-submit')!.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(vm.handleSubmit).not.toHaveBeenCalled()
    expect(byTestId('address-form-address-hint')!.textContent?.trim()).toBe('주소를 검색해 선택해 주세요.')

    vm.form.zonecode = '06236'
    vm.form.addressRoad = '서울 강남구 테헤란로 152'
    await flushPromises()
    expect(byTestId('address-form-address-hint')!.textContent?.trim()).toBe('')
    byTestId('address-form-submit')!.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(vm.handleSubmit).toHaveBeenCalledTimes(1)
  })

  it('폼 모달 닫힘 → 열려 있는 동안은 초기화 없음 · 닫힘이 끝나면(close-auto-focus) 뷰가 resetForm 1회', async () => {
    const vm = addressesVm()
    mounted = await mountSuspended(AddressesView, { props: { vm }, attachTo: document.body })
    vm.openCreate()
    await flushPromises()
    expect(byTestId('address-form-dialog')).not.toBeNull()
    expect(vm.resetForm).not.toHaveBeenCalled()
    vm.closeForm()
    await flushPromises()
    expect(byTestId('address-form-dialog')).toBeNull()
    expect(vm.resetForm).toHaveBeenCalledTimes(1)
  })

  it('삭제 확인 pending(removing) → 확인 잠금·"삭제 중…"', async () => {
    const vm = addressesVm()
    mounted = await mountSuspended(AddressesView, { props: { vm }, attachTo: document.body })
    vm.requestRemove(2)
    vm.removing = true
    await flushPromises()
    const confirm = byTestId('dialog-confirm-action') as HTMLButtonElement
    expect(confirm.disabled).toBe(true)
    expect(confirm.textContent?.trim()).toBe('삭제 중…')
    expect(confirm.getAttribute('aria-busy')).toBe('true')
  })
})
