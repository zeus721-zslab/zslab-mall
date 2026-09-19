<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminSellerBankAccountRow, AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import { ADMIN_SELLER_REASON_MAX, SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE } from '#layers/admin/app/lib/constants/admin-seller'
import { bankLabel, maskedAccountNumber, primaryBankAccount, primaryChangeHeadline } from '#layers/admin/app/lib/admin-seller-bank-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 주 정산계좌 전환 확인 다이얼로그(FE-41·BE D-188 PATCH …/primary 204·AdminSellerStatusDialog 패턴). 전환 안내 3문장(이후 지급·미지급 정산도 이 계좌·
 * 지급완료 건은 스냅샷이라 무영향)과 사유(필수·감사)를 받는다. 성공 후 부모가 상세를 다시 읽는다. 422(이미 주 계좌)·404는 토스트 후 stale.
 */
const props = defineProps<{ open: boolean; detail: AdminSellerDetail | null; target: AdminSellerBankAccountRow | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const lastTarget = ref<AdminSellerBankAccountRow | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const currentPrimary = computed(() => (props.detail ? primaryBankAccount(props.detail.bankAccounts) : null))
const headline = computed(() => (lastTarget.value ? primaryChangeHeadline(lastTarget.value) : ''))
const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.detail || !props.target) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await sellersApi.changePrimaryBankAccount(props.detail.sellerPublicId, props.target.id, { reason: trimmed })
    toast.success(`${bankLabel(props.target.bankCode)} ${maskedAccountNumber(props.target)} 계좌를 주 정산계좌로 지정했습니다. 이후 정산 지급은 이 계좌로 이루어집니다.`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else {
      // 422 SELLER_BANK_ACCOUNT_INVALID_STATE(이미 주 계좌)·404 → 화면이 오래됐을 수 있어 부모가 다시 읽는다.
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-bank-primary-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">주 정산계좌 전환</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 font-weight-medium mb-3" data-testid="seller-bank-primary-headline">{{ headline }}</p>
        <p v-if="currentPrimary" class="text-body-2 text-medium-emphasis mb-3" data-testid="seller-bank-primary-current">
          현재 주 계좌: {{ bankLabel(currentPrimary.bankCode) }} {{ maskedAccountNumber(currentPrimary) }} ({{ currentPrimary.accountHolder }}) — 전환 후 해제됩니다.
        </p>
        <v-alert type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="seller-bank-primary-notice">
          <p v-for="line in SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE" :key="line" class="text-body-2 mb-0" :class="{ 'font-weight-bold': line === SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE[0] }">{{ line }}</p>
        </v-alert>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          placeholder="예: 셀러 요청으로 정산계좌 변경(2026-09-18 접수)"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="seller-bank-primary-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-bank-primary-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-bank-primary-ok" @click="submit">주 계좌로 지정</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
