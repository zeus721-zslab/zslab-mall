import type { Address } from '~/types/address'
import type {
  ADDRESS_DETAIL_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_LABEL_MAX,
  ADDRESS_ROAD_MAX,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
} from '~/lib/constants/account'

/** 배송지 추가·수정 겸용 폼 값. 뷰가 v-model="vm.form.필드"로 쓴다. */
export interface AddressFormState {
  addressLabel: string
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun: string
  addressDetail: string
  isDefault: boolean
}

/** pages/mypage/addresses.vue → AddressesView. editingId가 null이면 추가, 값이 있으면 수정 모드. */
export interface AddressesPageVm {
  pending: boolean
  error: Error | undefined
  data: Address[] | undefined
  refresh: () => Promise<void>
  editingId: number | null
  form: AddressFormState
  submitting: boolean
  errorMessage: string
  successMessage: string
  resetForm: () => void
  startEdit: (address: Address) => void
  handleSubmit: () => Promise<void>
  handleSetDefault: (addressId: number) => Promise<void>
  /** window.confirm 확인 후 삭제(classic 경로). */
  handleRemove: (addressId: number) => Promise<void>
  /**
   * 폼 모달 열림(FE-72 보완 1). openCreate = 초기화 후 열기 · startEdit도 연다 · closeForm = 닫기만 · 저장 성공 시 닫힘.
   * 닫을 때 초기화하지 않는다 — 닫힘 애니메이션 중 입력칸이 비어 보이지 않도록 뷰가 닫힘이 끝난 뒤 resetForm을 부른다(FE-79).
   */
  formOpen: boolean
  openCreate: () => void
  closeForm: () => void
  /** 삭제 확인 모달 대상(null = 닫힘). confirmRemove는 window.confirm 없이 삭제하고, 끝날 때까지 모달을 열어 둔다(renew 경로). */
  removeTargetId: number | null
  /** confirmRemove 처리 중(FE-79) — 확인 모달 pending(버튼 잠금·닫힘 무시). */
  removing: boolean
  requestRemove: (addressId: number) => void
  cancelRemove: () => void
  confirmRemove: () => Promise<void>
  RECIPIENT_NAME_MAX: typeof RECIPIENT_NAME_MAX
  RECIPIENT_PHONE_MAX: typeof RECIPIENT_PHONE_MAX
  ADDRESS_LABEL_MAX: typeof ADDRESS_LABEL_MAX
  ZONECODE_MAX: typeof ZONECODE_MAX
  ADDRESS_ROAD_MAX: typeof ADDRESS_ROAD_MAX
  ADDRESS_JIBUN_MAX: typeof ADDRESS_JIBUN_MAX
  ADDRESS_DETAIL_MAX: typeof ADDRESS_DETAIL_MAX
}
