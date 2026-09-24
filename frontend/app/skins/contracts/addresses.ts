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
  handleRemove: (addressId: number) => Promise<void>
  RECIPIENT_NAME_MAX: typeof RECIPIENT_NAME_MAX
  RECIPIENT_PHONE_MAX: typeof RECIPIENT_PHONE_MAX
  ADDRESS_LABEL_MAX: typeof ADDRESS_LABEL_MAX
  ZONECODE_MAX: typeof ZONECODE_MAX
  ADDRESS_ROAD_MAX: typeof ADDRESS_ROAD_MAX
  ADDRESS_JIBUN_MAX: typeof ADDRESS_JIBUN_MAX
  ADDRESS_DETAIL_MAX: typeof ADDRESS_DETAIL_MAX
}
