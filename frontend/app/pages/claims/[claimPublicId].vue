<script setup lang="ts">
import {
  CLAIM_CANCEL_WARNING,
  CLAIM_INSPECTION_RESULT_LABELS,
  CLAIM_REASON_LABELS,
  REFUND_TIMING_NOTICE,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  refundStatusLabel,
} from '~/lib/constants/claim'
import {
  DELIVERY_CARRIER_CODES,
  DELIVERY_CARRIER_LABELS,
  DELIVERY_TRACKING_NO_MAX,
  deliveryCarrierLabel,
  type DeliveryCarrier,
} from '~/lib/constants/delivery'
import { formatDateTime } from '~/lib/utils/datetime'
import { claimStageGuide, claimTimeline, type TimelineStep, type TimelineStepState } from '~/lib/utils/claim-timeline'
import { tabOfClaimType } from '~/lib/constants/order-tabs'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const claimPublicId = route.params.claimPublicId as string

const { data, pending, error, refresh } = useClaimDetail(claimPublicId)

// FE-63: 목록 복귀는 통합된 주문내역의 자기 유형 탭으로 간다. 조회 전에는 유형을 모르므로 취소 탭을 기본으로 둔다.
const listTab = computed(() => (data.value ? tabOfClaimType(data.value.claimType) : 'cancel'))

// 401(세션 만료)은 /login 유도. 404(타인·미존재)는 존재 은닉이라 안내만(orders/[id] 패턴).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/claims/${claimPublicId}`)}`)
    }
  },
  { immediate: true },
)

// 404와 그 외 오류 문구 구분(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  (error.value as { statusCode?: number } | null)?.statusCode === 404
    ? '클레임을 찾을 수 없습니다'
    : '클레임을 불러오지 못했습니다',
)

// 진행 타임라인(FE-29·순수 함수 분리): 취소·교환 3단, 반품 6단(검수 불합격은 5단 종결).
const timeline = computed<TimelineStep[]>(() => (data.value ? claimTimeline(data.value) : []))
// 현재 단계 안내 1줄(FE-53·C-16): 무엇을 기다리는지·구매자가 할 일. 소요 기간은 시스템이 보장하지 않아 적지 않는다.
const stageGuide = computed<string>(() => (data.value ? claimStageGuide(data.value) : ''))

// 회수 송장 폼(반품 승인 후·송장 미등록·미회수일 때만·BE returnShipmentRequired).
const { registerReturnShipment, cancelClaim } = useClaim()

// 요청 취소(Track 101-A): 접수 상태에서만. 확인 1회 후 호출한다(되돌릴 수 없는 종결이라 즉시 실행하지 않는다).
const cancelConfirmOpen = ref<boolean>(false)
const cancelSubmitting = ref<boolean>(false)
const cancelError = ref<string>('')
const cancellable = computed<boolean>(() => data.value?.status === 'REQUESTED')

async function submitCancel(): Promise<void> {
  if (cancelSubmitting.value) return
  cancelSubmitting.value = true
  cancelError.value = ''
  try {
    await cancelClaim(claimPublicId)
    cancelConfirmOpen.value = false
    await refresh()
  } catch (submitError) {
    const statusCode = (submitError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/claims/${claimPublicId}`)}`)
      return
    }
    cancelConfirmOpen.value = false
    if (statusCode === 422) {
      // 그사이 관리자가 승인·거부했거나 이미 취소된 경우: 최신 상태를 보여 준다.
      cancelError.value = '이미 처리가 시작되어 취소할 수 없습니다. 최신 상태를 확인해 주세요.'
      await refresh()
    } else if (statusCode === 404) {
      cancelError.value = '클레임을 찾을 수 없습니다.'
    } else {
      cancelError.value = '요청 취소에 실패했습니다. 잠시 후 다시 시도하세요.'
    }
  } finally {
    cancelSubmitting.value = false
  }
}
const shipmentCarrier = ref<DeliveryCarrier | ''>('')
const shipmentTrackingNo = ref<string>('')
const shipmentSubmitting = ref<boolean>(false)
const shipmentError = ref<string>('')

async function submitReturnShipment(): Promise<void> {
  if (shipmentSubmitting.value) return
  const trackingNo = shipmentTrackingNo.value.trim()
  if (!shipmentCarrier.value) {
    shipmentError.value = '택배사를 선택하세요.'
    return
  }
  if (trackingNo === '' || trackingNo.length > DELIVERY_TRACKING_NO_MAX) {
    shipmentError.value = `송장번호를 ${DELIVERY_TRACKING_NO_MAX}자 이내로 입력하세요.`
    return
  }
  shipmentSubmitting.value = true
  shipmentError.value = ''
  try {
    await registerReturnShipment(claimPublicId, { carrier: shipmentCarrier.value, trackingNo })
    await refresh()
  } catch (submitError) {
    // 422(이미 등록·상태 경합)는 재조회로 최신 상태를 보여주고, 그 외는 타입별 문구(.catch(()=>{}) 금지).
    const statusCode = (submitError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/claims/${claimPublicId}`)}`)
      return
    }
    if (statusCode === 422) {
      shipmentError.value = '이미 등록되었거나 현재 상태에서는 회수 송장을 등록할 수 없습니다.'
      await refresh()
    } else if (statusCode === 400) {
      shipmentError.value = '택배사와 송장번호를 확인하세요.'
    } else {
      shipmentError.value = '회수 송장 등록에 실패했습니다. 잠시 후 다시 시도하세요.'
    }
  } finally {
    shipmentSubmitting.value = false
  }
}

function stepCircleClass(state: TimelineStepState): string {
  if (state === 'current') return 'bg-primary text-white ring-2 ring-primary ring-offset-2'
  if (state === 'done') return 'bg-primary text-white'
  return 'bg-gray-100 text-sub'
}

useSeoMeta({ title: '클레임 상세 · zslab-mall', description: 'zslab-mall 클레임 상세' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="error || !data" :message="errorMessage" @retry="refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 클레임 유형 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">클레임 유형</p>
            <h1 class="mt-1 text-lg font-medium text-ink">{{ claimTypeLabel(data.claimType) }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ claimStatusLabel(data.status) }}
          </span>
        </div>

        <!-- 요청 취소(Track 101-A): 접수 상태에서만 노출. 승인 이후에는 운영자 판단이 필요해 버튼을 감춘다. -->
        <div v-if="cancellable" class="mb-6" data-testid="claim-cancel-block">
          <template v-if="!cancelConfirmOpen">
            <Button variant="outline" size="sm" data-testid="claim-cancel-open" @click="cancelConfirmOpen = true">
              요청 취소
            </Button>
          </template>
          <div v-else class="rounded-card border border-line bg-gray-50 p-4" data-testid="claim-cancel-panel">
            <p class="text-sm font-medium text-ink">{{ claimTypeLabel(data.claimType) }} 요청을 취소할까요?</p>
            <p class="mt-1 text-sm text-soldout" style="white-space: pre-line" data-testid="claim-cancel-warning">{{ CLAIM_CANCEL_WARNING }}</p>
            <div class="mt-3 flex gap-2">
              <Button variant="destructive" size="sm" :disabled="cancelSubmitting" data-testid="claim-cancel-submit" @click="submitCancel">
                {{ cancelSubmitting ? '취소 중…' : '요청 취소' }}
              </Button>
              <Button variant="outline" size="sm" :disabled="cancelSubmitting" data-testid="claim-cancel-dismiss" @click="cancelConfirmOpen = false">
                닫기
              </Button>
            </div>
          </div>
          <p v-if="cancelError" class="mt-2 text-sm text-soldout" data-testid="claim-cancel-error">{{ cancelError }}</p>
        </div>
        <p v-else-if="cancelError" class="mb-6 text-sm text-soldout" data-testid="claim-cancel-error">{{ cancelError }}</p>

        <!-- 진행 타임라인 -->
        <section class="rounded-card border border-line p-5">
          <h2 class="mb-5 text-base font-semibold text-ink">진행 상태</h2>
          <ol class="flex items-start">
            <template v-for="(step, index) in timeline" :key="step.label">
              <li class="flex min-w-[3rem] flex-col items-center gap-1.5 text-center">
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full text-xs font-semibold"
                  :class="stepCircleClass(step.state)"
                >
                  {{ index + 1 }}
                </span>
                <span
                  class="text-xs"
                  :class="step.state === 'upcoming' ? 'text-sub' : 'font-medium text-ink'"
                >
                  {{ step.label }}
                </span>
                <span v-if="step.at" class="text-[11px] text-sub">{{ formatDateTime(step.at) }}</span>
              </li>
              <!-- 연결선: 이전 스텝을 통과(done)했으면 강조. 원 중심 높이에 맞춰 정렬. -->
              <span
                v-if="index < timeline.length - 1"
                class="mx-1 mt-4 h-0.5 flex-1"
                :class="step.state === 'done' ? 'bg-primary' : 'bg-line'"
              ></span>
            </template>
          </ol>
          <p class="mt-4 text-sm text-ink" data-testid="claim-stage-guide">{{ stageGuide }}</p>
        </section>

        <!-- 클레임 정보 -->
        <section class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">클레임 정보</h2>
          <dl class="space-y-3 text-sm">
            <div class="flex justify-between gap-4">
              <dt class="text-sub">사유</dt>
              <dd class="text-right text-ink">{{ CLAIM_REASON_LABELS[data.reasonCode] }}</dd>
            </div>
            <div v-if="data.reasonDetail" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">상세 사유</dt>
              <dd class="whitespace-pre-line text-right text-ink">{{ data.reasonDetail }}</dd>
            </div>
            <!-- 교환 옵션(FE-30·D-177): EXCHANGE만·라벨이 둘 다 없으면 행 숨김 -->
            <div v-if="data.claimType === 'EXCHANGE' && (data.originalOptionLabel || data.exchangeOptionLabel)" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">교환 옵션</dt>
              <dd class="text-right text-ink" data-testid="claim-exchange-option">
                {{ data.originalOptionLabel ?? '—' }} → {{ data.exchangeOptionLabel ?? '—' }}
              </dd>
            </div>
            <div class="flex justify-between gap-4">
              <dt class="text-sub">요청 일시</dt>
              <dd class="text-right text-ink">{{ formatDateTime(data.requestedAt) }}</dd>
            </div>
            <div v-if="data.processedAt" class="flex justify-between gap-4">
              <dt class="text-sub">처리 일시</dt>
              <dd class="text-right text-ink">{{ formatDateTime(data.processedAt) }}</dd>
            </div>
            <!-- 반품 회수·검수·재발송(FE-29·Track 81-A): 값이 있을 때만 행 노출 -->
            <div v-if="data.returnShipment" class="flex justify-between gap-4">
              <dt class="text-sub">회수 송장</dt>
              <dd class="text-right text-ink" data-testid="claim-return-shipment">
                {{ deliveryCarrierLabel(data.returnShipment.carrier) }} {{ data.returnShipment.trackingNo }}
              </dd>
            </div>
            <div v-if="data.pickedUpAt" class="flex justify-between gap-4">
              <dt class="text-sub">회수 확인</dt>
              <dd class="text-right text-ink" data-testid="claim-picked-up-at">{{ formatDateTime(data.pickedUpAt) }}</dd>
            </div>
            <div v-if="data.inspectionResult" class="flex justify-between gap-4">
              <dt class="text-sub">검수 결과</dt>
              <dd class="text-right text-ink" data-testid="claim-inspection-result">{{ CLAIM_INSPECTION_RESULT_LABELS[data.inspectionResult] }}</dd>
            </div>
            <div v-if="data.reshipment" class="flex justify-between gap-4">
              <dt class="text-sub">{{ data.claimType === 'EXCHANGE' ? '교환품 배송 송장' : '재발송 송장' }}</dt>
              <dd class="text-right text-ink" data-testid="claim-reshipment">
                {{ deliveryCarrierLabel(data.reshipment.carrier) }} {{ data.reshipment.trackingNo }}
              </dd>
            </div>
            <!-- 거부 사유·메모·환불 상태(FE-28·Track 80 D-169): 값이 있을 때만 행 노출 -->
            <div v-if="data.rejectReasonCode" class="flex justify-between gap-4">
              <dt class="text-sub">거부 사유</dt>
              <dd class="text-right text-ink" data-testid="claim-reject-reason">{{ claimRejectReasonLabel(data.rejectReasonCode) }}</dd>
            </div>
            <div v-if="data.rejectMemo" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">거부 메모</dt>
              <dd class="whitespace-pre-line text-right text-ink" data-testid="claim-reject-memo">{{ data.rejectMemo }}</dd>
            </div>
            <div v-if="data.refundStatus" class="flex justify-between gap-4">
              <dt class="text-sub">환불 상태</dt>
              <dd class="text-right text-ink" data-testid="claim-refund-status">{{ refundStatusLabel(data.refundStatus) }}</dd>
            </div>
          </dl>
          <!-- 환불 반영 시점 안내(FE-61): 환불이 걸린 클레임에서만·기간은 적지 않는다. -->
          <p v-if="data.refundStatus" class="mt-3 text-xs text-sub" data-testid="claim-refund-timing">{{ REFUND_TIMING_NOTICE }}</p>

          <!-- 첨부 사진(FE-29·Track 81-B): 순서 보존·클릭 시 원본 -->
          <div v-if="data.attachmentUrls && data.attachmentUrls.length > 0" class="mt-4">
            <p class="mb-2 text-sm text-sub">첨부 사진</p>
            <ul class="grid grid-cols-5 gap-2" data-testid="claim-attachments">
              <li v-for="(url, index) in data.attachmentUrls" :key="url" class="aspect-square overflow-hidden rounded-control border border-line">
                <a :href="url" target="_blank" rel="noopener">
                  <img :src="url" :alt="`첨부 사진 ${index + 1}`" class="h-full w-full object-cover">
                </a>
              </li>
            </ul>
          </div>
        </section>

        <!-- 회수 송장 등록(FE-29): 반품 승인 후 구매자가 직접 등록. 등록되면 BE가 returnShipmentRequired=false로 내려 폼이 사라진다. -->
        <section v-if="data.returnShipmentRequired" class="mt-6 rounded-card border border-line p-5" data-testid="claim-return-shipment-form">
          <h2 class="mb-1 text-base font-semibold text-ink">회수 송장 등록</h2>
          <p class="mb-4 text-sm text-sub" data-testid="claim-return-shipment-guide">
            {{ data.claimType === 'EXCHANGE'
              ? '교환할 상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인하고 검수한 뒤 교환품을 발송합니다.'
              : '상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인한 뒤 검수를 진행합니다.' }}
          </p>
          <form class="space-y-3" @submit.prevent="submitReturnShipment">
            <div class="space-y-1.5">
              <label for="shipmentCarrier" class="block text-sm font-medium text-ink">택배사</label>
              <select
                id="shipmentCarrier"
                v-model="shipmentCarrier"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
              >
                <option value="" disabled>택배사를 선택하세요</option>
                <option v-for="code in DELIVERY_CARRIER_CODES" :key="code" :value="code">{{ DELIVERY_CARRIER_LABELS[code] }}</option>
              </select>
            </div>
            <div class="space-y-1.5">
              <label for="shipmentTrackingNo" class="block text-sm font-medium text-ink">송장번호</label>
              <input
                id="shipmentTrackingNo"
                v-model="shipmentTrackingNo"
                type="text"
                :maxlength="DELIVERY_TRACKING_NO_MAX"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="송장번호"
              >
            </div>
            <p v-if="shipmentError" role="alert" class="text-sm text-soldout" data-testid="claim-return-shipment-error">{{ shipmentError }}</p>
            <Button type="submit" size="lg" class="w-full" :disabled="shipmentSubmitting" data-testid="claim-return-shipment-submit">
              {{ shipmentSubmitting ? '등록 중…' : '회수 송장 등록' }}
            </Button>
          </form>
        </section>

        <!-- 목록으로(FE-63: 주문내역의 자기 유형 탭으로 복귀) -->
        <div class="mt-8">
          <Button variant="outline" size="lg" class="w-full" as-child>
            <NuxtLink :to="{ path: '/orders', query: { tab: listTab } }">주문 내역으로</NuxtLink>
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
