<script setup lang="ts">
import type { AdminClaimDetail } from '#layers/admin/app/types/admin-claim'
import type { AdminClaimRejectTarget } from '#layers/admin/app/components/admin/AdminClaimRejectDialog.vue'
import { CLAIM_REASON_LABELS, CLAIM_SUGGESTION_LABELS, type ClaimReasonCode, claimStatusLabel, claimTypeLabel } from '~/lib/constants/claim'
import { approveConfirmMessage } from '#layers/admin/app/lib/admin-claim-view'
import { claimSuggestionChipClass } from '#layers/admin/app/lib/admin-inbox-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatWon } from '#layers/admin/app/lib/format'
import { useAdminClaims } from '#layers/admin/app/composables/useAdminClaims'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 인박스 클레임 접수 패널(D-250 · 상세 패널 안). 단건 조회로 판단 정보·처리 제안·근거를 보이고 승인·거부를 한다. 승인 실행은 인박스용으로 따로 두고
 * (클레임 목록·주문 상세의 기존 승인 코드는 그대로), 거부는 기존 AdminClaimRejectDialog를 쓴다. 끝나면 processed로 알려 페이지가 다시 읽는다.
 */
const props = defineProps<{ claimPublicId: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const claimsApi = useAdminClaims()
const ordersApi = useAdminOrders()
const toast = useAdminToast()

const detail = ref<AdminClaimDetail | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(claimPublicId: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  detail.value = null
  try {
    const response = await claimsApi.get(claimPublicId)
    if (sequence !== loadSequence) return
    detail.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const claim = computed(() => detail.value?.claim ?? null)
const canApprove = computed(() => claim.value?.availableActions.includes('APPROVE') ?? false)
const canReject = computed(() => claim.value?.availableActions.includes('REJECT') ?? false)
const productName = computed(() => claim.value?.productName ?? '')

function reasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

// ---------- 승인 ----------
const approveOpen = ref(false)
const approveBusy = ref(false)

async function runApprove(): Promise<void> {
  const current = claim.value
  if (!current || approveBusy.value) return
  approveBusy.value = true
  try {
    await ordersApi.approveClaim(current.claimId)
    toast.info(`${claimTypeLabel(current.type)} 요청을 승인했습니다.`)
    approveOpen.value = false
    emit('processed', 'done')
  } catch (error) {
    approveOpen.value = false
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID') {
      // 조회~승인 사이 다른 처리로 상태가 바뀐 경우: 안내 후 페이지가 다시 읽는다.
      toast.warning(toAdminErrorMessage(error))
      emit('processed', 'stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    approveBusy.value = false
  }
}

// ---------- 거부 ----------
const rejectTarget = ref<AdminClaimRejectTarget | null>(null)

function openReject(): void {
  const current = claim.value
  if (!current) return
  rejectTarget.value = { claimId: current.claimId, type: current.type, productName: current.productName ?? '' }
}
function onRejected(outcome: 'done' | 'stale'): void {
  rejectTarget.value = null
  emit('processed', outcome)
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.claimPublicId, (claimPublicId) => {
  approveOpen.value = false
  rejectTarget.value = null
  void load(claimPublicId)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-claim-panel">
    <v-skeleton-loader v-if="loading" type="list-item-three-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-claim-error">{{ loadError }}</v-alert>
    <template v-else-if="claim">
      <div v-if="detail?.suggestion" class="mb-3" data-testid="inbox-claim-suggestion">
        <span :class="claimSuggestionChipClass(detail.suggestion.suggestion)">{{ CLAIM_SUGGESTION_LABELS[detail.suggestion.suggestion] }}</span>
        <span class="text-body-2 ml-2" data-testid="inbox-claim-suggestion-reason">{{ detail.suggestion.reason }}</span>
      </div>
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">유형 · 상태</div>
          <div class="text-body-2" data-testid="inbox-claim-type">{{ claimTypeLabel(claim.type) }} · {{ claimStatusLabel(claim.status) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">금액</div>
          <div class="text-body-2">{{ formatWon(claim.amount) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">사유</div>
          <div class="text-body-2" data-testid="inbox-claim-reason">{{ reasonLabel(claim.reasonCode) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">첨부</div>
          <div class="text-body-2">{{ claim.attachmentCount }}개</div>
        </v-col>
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">옵션 · 수량</div>
          <div class="text-body-2">
            {{ claim.optionLabel ?? '-' }} · {{ claim.quantity }}개
            <span v-if="claim.exchangeOptionLabel"> → 교환 {{ claim.exchangeOptionLabel }}</span>
          </div>
        </v-col>
        <v-col v-if="claim.reasonDetail" cols="12">
          <div class="text-caption text-medium-emphasis">상세 사유</div>
          <div class="text-body-2" style="white-space: pre-line">{{ claim.reasonDetail }}</div>
        </v-col>
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">구매자</div>
          <div class="text-body-2">{{ claim.buyerName ?? '-' }}<span v-if="claim.buyerEmail"> · {{ claim.buyerEmail }}</span></div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn v-if="canApprove" color="primary" variant="flat" data-testid="inbox-claim-approve" @click="approveOpen = true">승인</v-btn>
        <v-btn v-if="canReject" color="error" variant="outlined" data-testid="inbox-claim-reject" @click="openReject">거부</v-btn>
      </div>
    </template>

    <AdminConfirmDialog
      :open="approveOpen"
      title="클레임 승인"
      :message="claim ? approveConfirmMessage(claim.type, productName) : ''"
      confirm-label="승인"
      :loading="approveBusy"
      risk
      test-id="inbox-claim-approve-dialog"
      @confirm="runApprove"
      @cancel="approveOpen = false"
    />
    <AdminClaimRejectDialog :open="rejectTarget !== null" :target="rejectTarget" @done="onRejected('done')" @stale="onRejected('stale')" @cancel="rejectTarget = null" />
  </div>
</template>
