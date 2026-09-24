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
import type { ClaimDetailPageVm } from '~/skins/contracts/claim-detail'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const claimPublicId = route.params.claimPublicId as string

const { data, pending, error, refresh } = useClaimDetail(claimPublicId)

// FE-63 → FE-73: 목록 복귀는 주문내역의 취소·반품·교환 탭(유형 구분 없는 한 탭)으로 간다.
const listTab = computed(() => (data.value ? tabOfClaimType(data.value.claimType) : 'claim'))

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

const vm: ClaimDetailPageVm = reactive({
  pending,
  error,
  data,
  refresh,
  errorMessage,
  listTab,
  timeline,
  stageGuide,
  stepCircleClass,
  cancellable,
  cancelConfirmOpen,
  cancelSubmitting,
  cancelError,
  submitCancel,
  shipmentCarrier,
  shipmentTrackingNo,
  shipmentSubmitting,
  shipmentError,
  submitReturnShipment,
  CLAIM_CANCEL_WARNING,
  CLAIM_INSPECTION_RESULT_LABELS,
  CLAIM_REASON_LABELS,
  REFUND_TIMING_NOTICE,
  DELIVERY_CARRIER_CODES,
  DELIVERY_CARRIER_LABELS,
  DELIVERY_TRACKING_NO_MAX,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  refundStatusLabel,
  deliveryCarrierLabel,
  formatDateTime,
})
</script>

<template>
  <component :is="useSkinView('ClaimDetailView')" :vm="vm" />
</template>
