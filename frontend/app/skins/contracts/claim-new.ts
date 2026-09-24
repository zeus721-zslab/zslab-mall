import type { ProductDetail } from '~/types/product'
import type { ClaimAttachedPhoto } from '~/components/claim/AttachmentInput.vue'
import type { ExchangeOptionCandidate } from '~/lib/utils/claim-exchange-options'
import type { CLAIM_REASON_LABELS, REFUND_TIMING_NOTICE, ClaimReasonCode, ClaimType } from '~/lib/constants/claim'

/**
 * pages/claims/new.vue → ClaimNewView. isValidQuery가 false면 잘못된 접근 안내, submitted면 접수 완료 화면.
 * 사유·상세 사유·교환 옵션·첨부는 뷰가 v-model로 쓴다(첨부는 ClaimAttachmentInput의 v-model).
 */
export interface ClaimNewPageVm {
  isValidQuery: boolean
  submitted: boolean
  claimType: ClaimType | null
  isExchange: boolean
  typeLabel: string
  typeGuidance: string
  productName: string
  reasonCodes: ClaimReasonCode[]
  reasonCode: ClaimReasonCode | ''
  reasonDetail: string
  REASON_DETAIL_MAX: number
  optionsPending: boolean
  optionsStatus: 'idle' | 'pending' | 'success' | 'error'
  refreshOptions: () => Promise<void>
  productDetail: ProductDetail | undefined
  exchangeOptions: ExchangeOptionCandidate[]
  exchangeVariantId: string
  attachmentAllowed: boolean
  attachments: ClaimAttachedPhoto[]
  submitting: boolean
  submitDisabled: boolean
  errorMessage: string
  handleSubmit: () => Promise<void>
  CLAIM_REASON_LABELS: typeof CLAIM_REASON_LABELS
  REFUND_TIMING_NOTICE: typeof REFUND_TIMING_NOTICE
}
