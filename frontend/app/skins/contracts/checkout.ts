import type { Address } from '~/types/address'
import type { PaymentMethod } from '~/types/checkout'
import type { CheckoutSummary } from '~/lib/utils/checkout-summary'
import type {
  ADDRESS_DETAIL_MAX,
  ADDRESS_JIBUN_MAX,
  ADDRESS_ROAD_MAX,
  RECIPIENT_NAME_MAX,
  RECIPIENT_PHONE_MAX,
  ZONECODE_MAX,
} from '~/lib/constants/account'
import type { PAYMENT_METHODS } from '~/lib/constants/payment'

/**
 * pages/checkout/index.vue → CheckoutView. 배송지 7필드·결제수단·저장 체크는 뷰가 v-model로 쓴다.
 * selectedKey 변경 시 뷰는 onSelectAddress를 호출해 폼을 저장 주소로 채우거나 비운다.
 */
export interface CheckoutPageVm {
  cartError: Error | undefined
  refreshCart: () => Promise<void>
  summary: CheckoutSummary
  addressLoadFailed: boolean
  hasAddresses: boolean
  addressList: Address[] | undefined
  selectedKey: string
  onSelectAddress: () => void
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun: string
  addressDetail: string
  deliveryMemo: string
  showSaveCheckbox: boolean
  saveAddress: boolean
  method: PaymentMethod
  errorMessage: string
  showCartLink: boolean
  submitting: boolean
  canSubmit: boolean
  formatPrice: (value: number) => string
  handleSubmit: () => Promise<void>
  PAYMENT_METHODS: typeof PAYMENT_METHODS
  RECIPIENT_NAME_MAX: typeof RECIPIENT_NAME_MAX
  RECIPIENT_PHONE_MAX: typeof RECIPIENT_PHONE_MAX
  ZONECODE_MAX: typeof ZONECODE_MAX
  ADDRESS_ROAD_MAX: typeof ADDRESS_ROAD_MAX
  ADDRESS_JIBUN_MAX: typeof ADDRESS_JIBUN_MAX
  ADDRESS_DETAIL_MAX: typeof ADDRESS_DETAIL_MAX
}
