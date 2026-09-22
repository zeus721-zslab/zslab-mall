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
// 상품 판매중지·후보 0건이면 신청 자체가 불가(BE도 422). 조회 중·실패는 제출을 막고 안내만 한다.
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
  errorMessage.value = claimRequestErrorMessage(submitError) ?? '클레임 요청에 실패했습니다. 잠시 후 다시 시도하세요.'
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

useSeoMeta({ title: '클레임 요청 · zslab-mall', description: 'zslab-mall 클레임 요청' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[640px] px-4 md:px-6">
      <!-- 필수 query 누락·부정: 폼 진입 차단하고 주문 내역으로 유도(재시도 무의미이므로 CommonErrorState 대신 링크 안내). -->
      <div v-if="!isValidQuery" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <p class="text-sub">잘못된 접근입니다. 주문 상세에서 클레임을 요청해 주세요.</p>
        <Button variant="outline" size="lg" as-child>
          <NuxtLink to="/orders">주문 내역으로</NuxtLink>
        </Button>
      </div>

      <!-- 제출 성공: 인라인 성공 상태(toast 인프라 부재). 원주문 id 미보유라 주문 내역으로 유도. -->
      <div v-else-if="submitted" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <p class="text-base font-medium text-ink">클레임이 접수되었습니다.</p>
        <p class="text-sm text-sub">쇼핑몰 승인 후 처리가 진행됩니다.</p>
        <!-- 환불 반영 시점 안내(FE-61): 환불로 이어지는 취소·반품 요청만·기간은 적지 않는다. -->
        <p v-if="claimType !== 'EXCHANGE'" class="text-xs text-sub" data-testid="claim-refund-timing">{{ REFUND_TIMING_NOTICE }}</p>
        <Button variant="outline" size="lg" as-child>
          <NuxtLink to="/orders">주문 내역으로</NuxtLink>
        </Button>
      </div>

      <template v-else>
        <NuxtLink to="/orders" class="mb-4 inline-block text-sm text-sub hover:underline">← 주문 내역</NuxtLink>
        <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">{{ typeLabel }} 요청</h1>

        <!-- 대상·안내 -->
        <section class="mb-6 rounded-card border border-line p-5">
          <p class="text-sm text-sub">요청 대상</p>
          <p class="mt-1 text-base font-medium text-ink">{{ productName || '주문 품목' }}</p>
          <p class="mt-3 text-sm text-ink">{{ typeGuidance }}</p>
          <p class="mt-1 text-sm text-sub">요청 후 쇼핑몰 승인이 필요합니다.</p>
        </section>

        <!-- 입력 폼 -->
        <form class="space-y-4" @submit.prevent="handleSubmit">
          <div class="space-y-1.5">
            <label for="reasonCode" class="block text-sm font-medium text-ink">요청 사유</label>
            <select
              id="reasonCode"
              v-model="reasonCode"
              required
              class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            >
              <option value="" disabled>사유를 선택하세요</option>
              <option v-for="code in reasonCodes" :key="code" :value="code">
                {{ CLAIM_REASON_LABELS[code] }}
              </option>
            </select>
          </div>

          <div class="space-y-1.5">
            <label for="reasonDetail" class="block text-sm font-medium text-ink">상세 사유 (선택)</label>
            <textarea
              id="reasonDetail"
              v-model="reasonDetail"
              :maxlength="REASON_DETAIL_MAX"
              rows="4"
              class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
              placeholder="상세 사유를 입력하세요(선택)"
            ></textarea>
            <p class="text-right text-xs text-sub">{{ reasonDetail.length }}/{{ REASON_DETAIL_MAX }}</p>
          </div>

          <!-- 교환 옵션 선택(FE-30-1): 같은 가격·판매 중 옵션만 후보. 0건이면 신청 불가 안내. -->
          <fieldset v-if="isExchange" class="space-y-1.5" data-testid="exchange-options">
            <legend class="block text-sm font-medium text-ink">교환할 옵션</legend>
            <p v-if="optionsPending" class="text-sm text-sub" data-testid="exchange-options-loading">교환 가능한 옵션을 불러오는 중…</p>
            <div v-else-if="optionsStatus === 'error'" class="flex items-center justify-between gap-3 rounded-control border border-line px-4 py-3" data-testid="exchange-options-error">
              <p class="text-sm text-soldout">교환 가능한 옵션을 불러오지 못했습니다.</p>
              <Button type="button" variant="outline" size="sm" @click="refreshOptions()">다시 시도</Button>
            </div>
            <p v-else-if="productDetail?.saleStopped" class="rounded-control border border-line px-4 py-3 text-sm text-sub" data-testid="exchange-options-empty">
              판매가 중지된 상품은 교환할 수 없습니다. 반품을 이용해 주세요.
            </p>
            <p v-else-if="exchangeOptions.length === 0" class="rounded-control border border-line px-4 py-3 text-sm text-sub" data-testid="exchange-options-empty">
              같은 가격으로 교환 가능한 다른 옵션이 없습니다. 반품을 이용해 주세요.
            </p>
            <ul v-else class="space-y-2">
              <li v-for="option in exchangeOptions" :key="option.variantPublicId">
                <label class="flex cursor-pointer items-center gap-3 rounded-control border border-line px-4 py-2.5 text-sm text-ink has-checked:border-gray-900">
                  <input v-model="exchangeVariantId" type="radio" name="exchangeVariantId" :value="option.variantPublicId" :disabled="submitting" data-testid="exchange-option">
                  <span>{{ option.label }}</span>
                </label>
              </li>
            </ul>
          </fieldset>

          <!-- 반품·교환 사진(FE-29·FE-30): 상품불량·오배송 사유에서만 노출·선택·최대 5장 -->
          <ClaimAttachmentInput v-if="attachmentAllowed" v-model="attachments" :disabled="submitting" />

          <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

          <Button type="submit" size="lg" class="w-full" :disabled="submitDisabled" data-testid="claim-submit">
            {{ submitting ? '요청 중…' : `${typeLabel} 요청하기` }}
          </Button>
        </form>
      </template>
    </div>
  </div>
</template>
