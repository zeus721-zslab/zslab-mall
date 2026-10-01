<script setup lang="ts">
import type { AdminSettlementDetail } from '#layers/admin/app/types/admin-settlement'
import { settlementConfirmMessage, settlementPayMessage } from '#layers/admin/app/lib/admin-risk-confirm'
import {
  canConfirm,
  canPay,
  formatBankAccount,
  formatDateOnly,
  formatSettlementPeriod,
  payBlockedReason,
  settlementStatusLabel,
} from '#layers/admin/app/lib/admin-settlement-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatWon } from '#layers/admin/app/lib/format'
import { useAdminSettlements } from '#layers/admin/app/composables/useAdminSettlements'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 인박스 정산 확정·지급 대기 패널(D-251 · 상세 패널 안). 정산 단건 조회로 금액·지급 예정일·계좌를 보이고, 정산 상세 화면과 같은 판정(canConfirm·canPay·
 * payBlockedReason)과 확인 문구로 확정·지급완료를 한다. 재생성은 두지 않는다(id가 바뀌어 원래 화면에서 한다).
 * 상태·계좌·음수 정산액 422는 정산 상세와 같이 경고 후 이 패널만 다시 읽는다 — 항목이 그대로 남아야 하는 경우라 processed를 보내지 않는다.
 */
const props = defineProps<{ settlementRef: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const RELOAD_ERROR_CODES: readonly string[] = ['SETTLEMENT_INVALID_STATE', 'SETTLEMENT_NET_NEGATIVE', 'SETTLEMENT_BANK_ACCOUNT_MISSING']

const settlementsApi = useAdminSettlements()
const toast = useAdminToast()

const detail = ref<AdminSettlementDetail | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(settlementRef: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  detail.value = null
  try {
    const response = await settlementsApi.get(Number(settlementRef))
    if (sequence !== loadSequence) return
    detail.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const periodLabel = computed(() => (detail.value ? formatSettlementPeriod(detail.value.periodStart) : ''))
const confirmAllowed = computed(() => (detail.value ? canConfirm(detail.value) : false))
const payAllowed = computed(() => (detail.value ? canPay(detail.value) : false))
const payBlocked = computed(() => (detail.value ? payBlockedReason(detail.value) : null))
const bankAccountText = computed(() => formatBankAccount(detail.value?.bankAccount))

const activeDialog = ref<'confirm' | 'pay' | null>(null)
const busy = ref(false)

async function runTransition(kind: 'confirm' | 'pay'): Promise<void> {
  const current = detail.value
  if (!current || busy.value) return
  busy.value = true
  try {
    if (kind === 'confirm') {
      await settlementsApi.confirm(current.id)
      toast.success(`${periodLabel.value} 정산을 확정했습니다. 셀러에게 공개되고 SMS가 발송됩니다.`)
    } else {
      await settlementsApi.pay(current.id)
      toast.success(`${periodLabel.value} 정산을 지급완료로 처리했습니다.`)
    }
    activeDialog.value = null
    emit('processed', 'done')
  } catch (error) {
    activeDialog.value = null
    if (RELOAD_ERROR_CODES.includes(extractErrorCode(error) ?? '')) {
      // 조회~처리 사이 상태·계좌가 바뀐 경우: 안내 후 최신 정산을 다시 읽는다(인박스 항목은 그대로).
      toast.warning(toAdminErrorMessage(error))
      await load(props.settlementRef)
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    busy.value = false
  }
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.settlementRef, (settlementRef) => {
  activeDialog.value = null
  void load(settlementRef)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-settlement-panel">
    <v-skeleton-loader v-if="loading" type="list-item-three-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-settlement-error">{{ loadError }}</v-alert>
    <template v-else-if="detail">
      <p v-if="payBlocked" class="text-caption text-error mb-2" data-testid="inbox-settlement-pay-blocked">{{ payBlocked }}</p>
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">기간 · 상태</div>
          <div class="text-body-2" data-testid="inbox-settlement-status">{{ periodLabel }} · {{ settlementStatusLabel(detail.status) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">지급예정일</div>
          <div class="text-body-2">{{ formatDateOnly(detail.scheduledPayDate) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">지급액</div>
          <div class="text-body-2 font-weight-medium" data-testid="inbox-settlement-net">{{ formatWon(detail.netAmount) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">매출 · 수수료 · 환불 · 이월</div>
          <div class="text-body-2">
            {{ formatWon(detail.grossAmount) }} · {{ formatWon(detail.feeAmount) }} · {{ formatWon(detail.refundAmount) }} · {{ formatWon(detail.carryoverAmount) }}
          </div>
        </v-col>
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">지급 계좌</div>
          <div class="text-body-2">{{ bankAccountText ?? '—' }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn v-if="confirmAllowed" variant="flat" class="op-risk-action" :disabled="busy" data-testid="inbox-settlement-confirm" @click="activeDialog = 'confirm'">확정</v-btn>
        <v-btn
          v-if="detail.status === 'CONFIRMED'"
          variant="flat"
          class="op-risk-action"
          :disabled="!payAllowed || busy"
          data-testid="inbox-settlement-pay"
          @click="activeDialog = 'pay'"
        >지급완료</v-btn>
      </div>
    </template>

    <AdminConfirmDialog
      :open="activeDialog === 'confirm'"
      title="정산 확정"
      :message="settlementConfirmMessage(periodLabel, detail?.seller.companyName ?? '')"
      confirm-label="확정"
      risk
      :loading="busy"
      test-id="inbox-settlement-confirm-dialog"
      @confirm="runTransition('confirm')"
      @cancel="activeDialog = null"
    />
    <AdminConfirmDialog
      :open="activeDialog === 'pay'"
      title="정산 지급완료"
      :message="settlementPayMessage(periodLabel, detail?.seller.companyName ?? '', formatWon(detail?.netAmount), bankAccountText ?? '—')"
      confirm-label="지급완료"
      risk
      :loading="busy"
      test-id="inbox-settlement-pay-dialog"
      @confirm="runTransition('pay')"
      @cancel="activeDialog = null"
    />
  </div>
</template>
