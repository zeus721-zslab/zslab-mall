<script setup lang="ts">
import { CLAIM_INSPECTION_FAIL_REASON_CODE, CLAIM_REJECT_MEMO_MAX, CLAIM_REJECT_REASON_LABELS, type ClaimInspectionResult, type ClaimType } from '~/lib/constants/claim'
import {
  ADMIN_DELIVERY_CARRIER_OPTIONS,
  ADMIN_ORDER_TRACKING_NO_MAX,
  type AdminDeliveryCarrier,
} from '#layers/admin/app/lib/constants/admin-order'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { inspectDialogTitle, inspectNoticeMessage, inspectPassLabel, inspectPassToast, validateInspectForm } from '#layers/admin/app/lib/admin-claim-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/** 검수 대상 최소 정보(목록 행). */
export interface AdminClaimInspectTarget {
  claimId: string
  productName: string
  /** 유형별 합격 의미 분기(반품 환불 / 교환 발송 대기·FE-30). 미지정은 반품. */
  claimType?: ClaimType
  /** 아직 회수 확인 전(CONFIRM_PICKUP 가능)이면 true — "회수 확인 후 검수" 체크를 요구하고 confirm-pickup → inspect 순차 호출(Track 96-1 FE-53·C-10). */
  pickupRequired?: boolean
}

/**
 * 반품 검수 다이얼로그(FE-29·Track 81-A D-170·D-172). 결과 PASS는 재입고 여부(필수), FAIL은 사유 "검수 불합격" 고정(입력 없음)·메모(구매자 안내에
 * 남기는 불합격 근거)·재발송 택배사·송장(필수).
 * 호출·토스트는 다이얼로그가 소유하며 PASS 성공 info(환불 자동 진행)·FAIL 성공 danger 토스트 후 done, 422(회수 전·이미 검수·경합)는 warning 후
 * stale(부모가 다시 읽음), 400은 fieldErrors 표시(AdminClaimRejectDialog 패턴). 처리 중에는 닫기·재제출을 막는다.
 *
 * <p>회수 확인 전 진입(pickupRequired·C-10): 체크 후 confirm-pickup을 먼저 호출하고 성공하면 inspect를 이어 호출한다. confirm-pickup 실패는 검수를
 * 시작하지 않는다. confirm-pickup 성공·inspect 실패는 "회수 확인은 반영됨"을 구분해 알리고 stale로 닫아 부모가 최신 행(INSPECT만 가능)을 다시 읽게 한다.
 */
const props = defineProps<{
  open: boolean
  target: AdminClaimInspectTarget | null
}>()
// done은 처리한 검수 결과를 싣는다 — 목록이 합격(교환)일 때만 교환품 발송을 이어 연다(FE-61).
const emit = defineEmits<{ done: [result: ClaimInspectionResult]; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const RESULT_OPTIONS = computed<{ value: ClaimInspectionResult; label: string }[]>(() => [
  { value: 'PASS', label: inspectPassLabel(claimType.value) },
  { value: 'FAIL', label: '불합격 (재발송)' },
])
const RESTOCK_OPTIONS: { value: boolean; label: string }[] = [
  { value: true, label: '재입고' },
  { value: false, label: '폐기 (재고 미복구)' },
]

const result = ref<ClaimInspectionResult | null>(null)
const restock = ref<boolean | null>(null)
const memo = ref('')
const reshipCarrier = ref<AdminDeliveryCarrier | null>(null)
const reshipTrackingNo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
// 회수 확인 후 검수(C-10): 체크 여부·이번 제출에서 confirm-pickup이 성공했는지(inspect 실패 안내 구분용).
const pickupChecked = ref(false)
const pickupApplied = ref(false)

// 닫힘 애니메이션 동안 상품명이 사라지지 않도록 마지막 대상을 유지한다(target은 즉시 null).
const lastTarget = ref<AdminClaimInspectTarget | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })
const claimType = computed<ClaimType>(() => lastTarget.value?.claimType ?? 'RETURN')
const pickupRequired = computed<boolean>(() => lastTarget.value?.pickupRequired ?? false)

function reset(): void {
  result.value = null
  restock.value = null
  memo.value = ''
  reshipCarrier.value = null
  reshipTrackingNo.value = ''
  errors.value = {}
  submitting.value = false
  pickupChecked.value = false
  pickupApplied.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

const confirmDisabled = computed(() => submitting.value || result.value === null || (pickupRequired.value && !pickupChecked.value))

/** 회수 확인 선행 호출. 실패 시 false(검수 미시작)·에러 처리는 기존 회수 확인 버튼과 같다. */
async function applyPickup(claimId: string): Promise<boolean> {
  try {
    await ordersApi.confirmPickupClaim(claimId)
    pickupApplied.value = true
    return true
  } catch (error) {
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID') {
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
    return false
  }
}

async function submit(): Promise<void> {
  if (submitting.value || !props.target) return
  const validation = validateInspectForm({
    result: result.value, restock: restock.value, memo: memo.value,
    reshipCarrier: reshipCarrier.value, reshipTrackingNo: reshipTrackingNo.value,
  })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !result.value) return

  submitting.value = true
  try {
    if (pickupRequired.value && !(await applyPickup(props.target.claimId))) return
    const submitted: ClaimInspectionResult = result.value
    if (result.value === 'PASS') {
      await ordersApi.inspectClaim(props.target.claimId, { result: 'PASS', restock: restock.value ?? false })
      toast.info(inspectPassToast(claimType.value))
    } else {
      await ordersApi.inspectClaim(props.target.claimId, {
        result: 'FAIL',
        rejectReasonCode: CLAIM_INSPECTION_FAIL_REASON_CODE,
        memo: memo.value.trim() || undefined,
        reshipCarrier: reshipCarrier.value ?? undefined,
        reshipTrackingNo: reshipTrackingNo.value.trim(),
      })
      toast.danger('검수 불합격 처리했습니다. 상품을 재발송합니다.') // 거부 종결은 부정적 의미
    }
    emit('done', submitted)
  } catch (error) {
    // 회수 확인이 이미 반영된 뒤 검수만 실패한 경우: 구분해 알리고 부모가 최신 행을 다시 읽는다(회수 확인은 유지·검수는 목록에서 재시도).
    if (pickupApplied.value) {
      toast.warning(`회수 확인은 반영되었습니다. 검수는 처리되지 않았습니다: ${toAdminErrorMessage(error)}`)
      emit('stale')
      return
    }
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      // 조건부 필수(BE IllegalArgumentException → 400)는 fieldErrors가 없으므로 결과별 대표 필드에 안내한다.
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0
        ? mapped
        : { [result.value === 'PASS' ? 'restock' : 'reshipTrackingNo']: toAdminErrorMessage(error) }
    } else if (code === 'CLAIM_STATE_INVALID') {
      // 회수 확인 전·이미 검수됨·동시 검수 경합: 안내 후 부모가 최신 데이터를 다시 읽는다.
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-claim-inspect-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ inspectDialogTitle(claimType) }}</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3" style="white-space: pre-line" data-testid="inspect-notice">{{ inspectNoticeMessage(claimType, lastTarget?.productName ?? '') }}</p>
        <v-checkbox
          v-if="pickupRequired"
          v-model="pickupChecked"
          label="회수 확인 후 검수 (회수품 도착을 확인했습니다)"
          hint="아직 회수 확인 전입니다. 체크하면 회수 확인을 먼저 처리한 뒤 검수합니다."
          persistent-hint
          density="compact"
          :disabled="submitting"
          class="mb-2"
          data-testid="inspect-pickup-check"
        />

        <v-radio-group
          :model-value="result"
          inline
          hide-details="auto"
          :error-messages="errors.result ? [errors.result] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="inspect-result"
          @update:model-value="(value) => { result = value ?? null; clearError('result') }"
        >
          <v-radio v-for="option in RESULT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" :data-testid="`inspect-result-${option.value}`" />
        </v-radio-group>

        <template v-if="result === 'PASS'">
          <v-radio-group
            :model-value="restock"
            inline
            hide-details="auto"
            label="재입고 여부"
            :error-messages="errors.restock ? [errors.restock] : []"
            :disabled="submitting"
            data-testid="inspect-restock"
            @update:model-value="(value) => { restock = value ?? null; clearError('restock') }"
          >
            <v-radio v-for="option in RESTOCK_OPTIONS" :key="String(option.value)" :label="option.label" :value="option.value" :data-testid="`inspect-restock-${option.value}`" />
          </v-radio-group>
        </template>

        <template v-else-if="result === 'FAIL'">
          <p class="text-body-2 mb-2" data-testid="inspect-reason">
            불합격 사유: <span class="font-weight-medium">{{ CLAIM_REJECT_REASON_LABELS[CLAIM_INSPECTION_FAIL_REASON_CODE] }}</span>
            <span class="text-medium-emphasis"> — 구매자에게 사유가 안내됩니다. 불합격 근거(사용 흔적·파손 등)는 메모에 남기세요.</span>
          </p>
          <v-textarea
            v-model="memo"
            label="메모 (선택·불합격 근거)"
            rows="2"
            auto-grow
            :maxlength="CLAIM_REJECT_MEMO_MAX"
            :counter="CLAIM_REJECT_MEMO_MAX"
            :error-messages="errors.memo ? [errors.memo] : []"
            :disabled="submitting"
            class="mb-2"
            data-testid="inspect-memo"
            @update:model-value="clearError('memo')"
          />
          <v-select
            :model-value="reshipCarrier"
            :items="ADMIN_DELIVERY_CARRIER_OPTIONS"
            label="재발송 택배사"
            :error-messages="errors.reshipCarrier ? [errors.reshipCarrier] : []"
            :disabled="submitting"
            class="mb-2"
            data-testid="inspect-reship-carrier"
            @update:model-value="(value) => { reshipCarrier = value ?? null; clearError('reshipCarrier') }"
          />
          <v-text-field
            v-model="reshipTrackingNo"
            label="재발송 송장번호"
            :maxlength="ADMIN_ORDER_TRACKING_NO_MAX"
            :counter="ADMIN_ORDER_TRACKING_NO_MAX"
            :error-messages="errors.reshipTrackingNo ? [errors.reshipTrackingNo] : []"
            :disabled="submitting"
            data-testid="inspect-reship-tracking-no"
            @update:model-value="clearError('reshipTrackingNo')"
            @keyup.enter="submit"
          />
        </template>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="inspect-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="inspect-dialog-ok" @click="submit">
          {{ result === 'FAIL' ? '불합격 처리' : '합격 처리' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
