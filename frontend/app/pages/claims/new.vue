<script setup lang="ts">
import {
  CLAIM_REASON_CODES,
  CLAIM_REASON_LABELS,
  CLAIM_TYPE_LABELS,
  isClaimType,
  type ClaimReasonCode,
  type ClaimType,
} from '~/lib/constants/claim'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const REASON_DETAIL_MAX = 500
const ORDER_ITEM_ID_PATTERN = /^oit_[0-9A-Z]{26}$/

// 진입점(주문 상세)이 넘긴 query. name은 표시용(서버 미전송). 진입 시점 1회 파싱으로 충분하다(라우트 변화 미대응).
const route = useRoute()
const orderItemPublicId = typeof route.query.orderItem === 'string' ? route.query.orderItem : ''
const typeParam = typeof route.query.type === 'string' ? route.query.type : ''
const productName = typeof route.query.name === 'string' ? route.query.name : ''

// 필수 query 검증: orderItem은 oit_ + ULID 26자(서버 정규식 동일)·type은 유효 ClaimType.
const claimType: ClaimType | null = isClaimType(typeParam) ? typeParam : null
const isValidQuery = ORDER_ITEM_ID_PATTERN.test(orderItemPublicId) && claimType !== null

// type별 안내 문구(승인 필요 고지는 공통).
const TYPE_GUIDANCE: Record<ClaimType, string> = {
  CANCEL: '승인 시 결제가 취소되고 환불됩니다.',
  RETURN: '승인 후 상품을 수거하고 환불이 진행됩니다.',
  EXCHANGE: '승인 후 상품을 수거하고 교환품을 재배송합니다. 차액이 발생할 수 있습니다.',
}
const typeLabel = computed<string>(() => (claimType ? CLAIM_TYPE_LABELS[claimType] : ''))
const typeGuidance = computed<string>(() => (claimType ? TYPE_GUIDANCE[claimType] : ''))

const { requestClaim } = useClaim()

const reasonCode = ref<ClaimReasonCode | ''>('')
const reasonDetail = ref<string>('')
const submitting = ref<boolean>(false)
const submitted = ref<boolean>(false)
const errorMessage = ref<string>('')

// 실패 응답 코드별 문구 분기(.catch(()=>{}) 금지·타입 구분). BE 실측: 404=미존재/타인, 422=중복·상태불가, 400=형식.
function handleSubmitError(submitError: { statusCode?: number }): void {
  const statusCode = submitError.statusCode
  if (statusCode === 401) {
    navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  if (statusCode === 422) {
    errorMessage.value = '이미 진행 중인 클레임이 있거나 현재 상태에서는 요청할 수 없습니다.'
    return
  }
  if (statusCode === 404) {
    errorMessage.value = '대상 주문 품목을 찾을 수 없습니다.'
    return
  }
  if (statusCode === 400) {
    errorMessage.value = '요청 정보를 확인하세요.'
    return
  }
  errorMessage.value = '클레임 요청에 실패했습니다. 잠시 후 다시 시도하세요.'
}

async function handleSubmit(): Promise<void> {
  if (submitting.value || claimType === null) return
  if (!reasonCode.value) {
    errorMessage.value = '요청 사유를 선택하세요.'
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
    })
    submitted.value = true
  } catch (submitError) {
    handleSubmitError(submitError as { statusCode?: number })
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
        <p class="text-sm text-sub">판매자 승인 후 처리가 진행됩니다.</p>
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
          <p class="mt-1 text-sm text-sub">요청 후 판매자 승인이 필요합니다.</p>
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
              <option v-for="code in CLAIM_REASON_CODES" :key="code" :value="code">
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

          <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

          <Button type="submit" size="lg" class="w-full" :disabled="submitting">
            {{ submitting ? '요청 중…' : `${typeLabel} 요청하기` }}
          </Button>
        </form>
      </template>
    </div>
  </div>
</template>
