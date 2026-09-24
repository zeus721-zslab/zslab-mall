<script setup lang="ts">
import {
  CLAIM_REASON_LABELS,
  CLAIM_TYPE_LABELS,
  REFUND_TIMING_NOTICE,
  claimReasonCodesFor,
  isClaimAttachmentAllowed,
  isClaimType,
  type ClaimReasonCode,
  type ClaimType,
} from '~/lib/constants/claim'
import { claimRequestErrorMessage, type ClaimRequestErrorLike } from '~/lib/utils/claim-request-error'
import { exchangeOptionCandidates, type ExchangeOptionCandidate } from '~/lib/utils/claim-exchange-options'
import type { ClaimAttachedPhoto } from '~/components/claim/AttachmentInput.vue'
import type { ClaimNewPageVm } from '~/skins/contracts/claim-new'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const REASON_DETAIL_MAX = 500
const ORDER_ITEM_ID_PATTERN = /^oit_[0-9A-Z]{26}$/
const PRODUCT_ID_PATTERN = /^prd_[0-9A-Z]{26}$/
const VARIANT_ID_PATTERN = /^var_[0-9A-Z]{26}$/

// 진입점(주문 상세)이 넘긴 query. name은 표시용(서버 미전송). 진입 시점 1회 파싱으로 충분하다(라우트 변화 미대응).
const route = useRoute()
const orderItemPublicId = typeof route.query.orderItem === 'string' ? route.query.orderItem : ''
const typeParam = typeof route.query.type === 'string' ? route.query.type : ''
const productName = typeof route.query.name === 'string' ? route.query.name : ''
// 교환(FE-30-1 α): 옵션 후보 조회·필터용 상품/변형 public id·주문 단가(주문 상세가 넘김·규칙 보장은 BE).
const productPublicId = typeof route.query.product === 'string' ? route.query.product : ''
const currentVariantPublicId = typeof route.query.variant === 'string' ? route.query.variant : ''
const unitPriceParam = typeof route.query.unitPrice === 'string' ? Number(route.query.unitPrice) : Number.NaN

// 필수 query 검증: orderItem은 oit_ + ULID 26자(서버 정규식 동일)·type은 유효 ClaimType. 교환은 product·variant·unitPrice까지 있어야 한다.
const claimType: ClaimType | null = isClaimType(typeParam) ? typeParam : null
const isExchange = claimType === 'EXCHANGE'
const isValidExchangeQuery = !isExchange
  || (PRODUCT_ID_PATTERN.test(productPublicId) && VARIANT_ID_PATTERN.test(currentVariantPublicId) && Number.isInteger(unitPriceParam))
const isValidQuery = ORDER_ITEM_ID_PATTERN.test(orderItemPublicId) && claimType !== null && isValidExchangeQuery

// type별 안내 문구(승인 필요 고지는 공통).
const TYPE_GUIDANCE: Record<ClaimType, string> = {
  CANCEL: '승인 시 결제가 취소되고 환불됩니다.',
  RETURN: '배송완료 후 7일 이내 요청할 수 있습니다. 승인 후 회수 송장을 등록하면 검수를 거쳐 환불이 진행됩니다.',
  EXCHANGE: '배송완료 후 7일 이내 요청할 수 있습니다. 승인 후 회수 송장을 등록하면 검수를 거쳐 교환품을 발송합니다. 같은 가격의 다른 옵션으로만 교환됩니다.',
}
const typeLabel = computed<string>(() => (claimType ? CLAIM_TYPE_LABELS[claimType] : ''))
const typeGuidance = computed<string>(() => (claimType ? TYPE_GUIDANCE[claimType] : ''))

const { requestClaim } = useClaim()

// 유형별 사유 목록(반품은 3값·D-170). 반품 사진은 상품불량·오배송에서만(D-171).
const reasonCodes = computed<ClaimReasonCode[]>(() => (claimType ? claimReasonCodesFor(claimType) : []))

const reasonCode = ref<ClaimReasonCode | ''>('')
const reasonDetail = ref<string>('')
const attachments = ref<ClaimAttachedPhoto[]>([])
const submitting = ref<boolean>(false)
const submitted = ref<boolean>(false)
const errorMessage = ref<string>('')

const attachmentAllowed = computed<boolean>(() => claimType !== null && isClaimAttachmentAllowed(claimType, reasonCode.value))

// 교환 옵션 후보(FE-30-1 α): 상품 상세를 조회해 같은 가격·품절 아님·현재 옵션 제외로 거른다. 교환이 아니면 조회하지 않는다(immediate false).
// Nuxt 4 useFetch의 error 초기값은 undefined라 null 비교 대신 status('error')로 판정한다.
const {
  data: productDetail,
  status: optionsStatus,
  refresh: refreshOptions,
} = useProductDetail(productPublicId, { immediate: isExchange && isValidQuery })
// immediate=false일 때 pending 초기값이 버전별로 달라 status로 판정한다(idle/pending/success/error).
const optionsPending = computed<boolean>(() => optionsStatus.value === 'pending')
const exchangeOptions = computed<ExchangeOptionCandidate[]>(() =>
  productDetail.value
    ? exchangeOptionCandidates(productDetail.value.variants, currentVariantPublicId, unitPriceParam)
    : [],
)
const exchangeVariantId = ref<string>('')
// 상품 판매중지·후보 0건이면 요청 자체가 불가(BE도 422). 조회 중·실패는 제출을 막고 안내만 한다.
const exchangeBlocked = computed<boolean>(() =>
  isExchange && (optionsPending.value || optionsStatus.value === 'error' || (productDetail.value?.saleStopped ?? false) || exchangeOptions.value.length === 0),
)
const submitDisabled = computed<boolean>(() => submitting.value || exchangeBlocked.value || (isExchange && exchangeVariantId.value === ''))

// 사유를 단순변심 등으로 바꾸면 첨부 목록을 비운다(첨부 불가 사유로 제출 시 BE 400).
watch(attachmentAllowed, (allowed) => {
  if (!allowed) attachments.value = []
})

// 실패 응답 코드별 문구 분기(.catch(()=>{}) 금지·타입 구분). BE 실측: 404=미존재/타인, 422=반품 조건·중복·상태불가(detail로 구분), 400=형식·첨부.
function handleSubmitError(submitError: ClaimRequestErrorLike): void {
  const statusCode = submitError.statusCode
  if (statusCode === 401) {
    navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  if (statusCode === 404) {
    errorMessage.value = '대상 주문 품목을 찾을 수 없습니다.'
    return
  }
  errorMessage.value = claimRequestErrorMessage(submitError) ?? '취소·반품·교환 요청에 실패했습니다. 잠시 후 다시 시도하세요.'
}

async function handleSubmit(): Promise<void> {
  if (submitting.value || claimType === null) return
  if (!reasonCode.value) {
    errorMessage.value = '요청 사유를 선택하세요.'
    return
  }
  if (isExchange && exchangeVariantId.value === '') {
    errorMessage.value = '교환할 옵션을 선택하세요.'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await requestClaim({
      orderItemPublicId,
      claimType,
      reasonCode: reasonCode.value,
      // 빈 문자열이면 undefined로 보내 서버에 저장하지 않는다($fetch가 undefined 키 생략).
      reasonDetail: reasonDetail.value.trim() || undefined,
      // 첨부는 허용 사유에서만·화면 순서 그대로(BE display_order).
      attachmentIds: attachmentAllowed.value && attachments.value.length > 0
        ? attachments.value.map((photo) => photo.attachmentId)
        : undefined,
      // 교환 옵션은 EXCHANGE에서만 보낸다(그 외 유형 지정 시 BE 400).
      exchangeVariantId: isExchange ? exchangeVariantId.value : undefined,
    })
    submitted.value = true
  } catch (submitError) {
    handleSubmitError(submitError as ClaimRequestErrorLike)
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '취소·반품·교환 요청 · zslab-mall', description: 'zslab-mall 취소·반품·교환 요청' })

const vm: ClaimNewPageVm = reactive({
  isValidQuery,
  submitted,
  claimType,
  isExchange,
  typeLabel,
  typeGuidance,
  productName,
  reasonCodes,
  reasonCode,
  reasonDetail,
  REASON_DETAIL_MAX,
  optionsPending,
  optionsStatus,
  refreshOptions,
  productDetail,
  exchangeOptions,
  exchangeVariantId,
  attachmentAllowed,
  attachments,
  submitting,
  submitDisabled,
  errorMessage,
  handleSubmit,
  CLAIM_REASON_LABELS,
  REFUND_TIMING_NOTICE,
})
</script>

<template>
  <component :is="useSkinView('ClaimNewView')" :vm="vm" />
</template>
