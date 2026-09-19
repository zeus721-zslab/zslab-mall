<script setup lang="ts">
import { mdiInformationOutline } from '@mdi/js'
import type { AdminSellerBankAccountRow, AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_BANK_OPTIONS,
  ADMIN_SELLER_ACCOUNT_HOLDER_MAX,
  ADMIN_SELLER_ACCOUNT_NUMBER_MAX,
  ADMIN_SELLER_REASON_MAX,
  SELLER_BANK_ACCOUNT_FIRST_PRIMARY_NOTICE,
} from '#layers/admin/app/lib/constants/admin-seller'
import { bankLabel, maskedAccountNumber, validateAccountNumberInput } from '#layers/admin/app/lib/admin-seller-bank-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 셀러 정산계좌 등록·수정 다이얼로그(FE-41·BE D-188 POST 201 / PUT 204·AdminSellerEditDialog 패턴). 은행 선택·계좌번호(숫자·하이픈·입력 중 마스킹 없음)·
 * 예금주를 받는다. 등록은 사유가 없고 첫 계좌면 자동 주 계좌 안내를 띄운다. 수정은 사유 필수이며 **기존 계좌번호를 채우지 않는다** — 응답에는 끝 4자리만
 * 오고 복호화된 전체 번호를 클라이언트로 내리지 않는 설계(D-188)와 일관되게 새 번호를 다시 입력받는다(끝 4자리는 참고용으로만 표시).
 * 성공 후 부모가 상세를 다시 읽는다. 400은 fieldErrors, 409 정산 참조는 토스트 + stale(화면 판정과 서버가 어긋난 경우), 404는 stale.
 */
const props = defineProps<{
  open: boolean
  detail: AdminSellerDetail | null
  /** null = 등록, 행 = 수정 */
  target: AdminSellerBankAccountRow | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const form = reactive({ bankCode: '', accountNumber: '', accountHolder: '' })
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 제목·문구가 바뀌지 않도록 마지막 대상을 유지한다.
const lastTarget = ref<AdminSellerBankAccountRow | null>(null)
watch(() => props.target, (next) => { lastTarget.value = next })

const isEdit = computed(() => lastTarget.value !== null)
const isFirstAccount = computed(() => !isEdit.value && (props.detail?.bankAccounts.length ?? 0) === 0)

function reset(): void {
  lastTarget.value = props.target
  Object.assign(form, {
    bankCode: props.target?.bankCode ?? '',
    accountNumber: '', // 수정에서도 기존 번호를 채우지 않는다(전체 번호는 클라이언트에 없음)
    accountHolder: props.target?.accountHolder ?? '',
  })
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

const confirmDisabled = computed(() =>
  submitting.value || form.bankCode === '' || form.accountNumber.trim() === '' || form.accountHolder.trim() === ''
  || (isEdit.value && reason.value.trim() === ''),
)

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  const localErrors: Record<string, string> = {}
  if (form.bankCode === '') localErrors.bankCode = '은행을 선택하세요.'
  const number = validateAccountNumberInput(form.accountNumber)
  if (!number.ok) localErrors.accountNumber = number.message
  if (form.accountHolder.trim() === '') localErrors.accountHolder = '예금주를 입력하세요.'
  if (isEdit.value && reason.value.trim() === '') localErrors.reason = '수정 사유를 입력하세요.'
  if (Object.keys(localErrors).length > 0 || !number.ok) {
    errors.value = localErrors
    return
  }
  submitting.value = true
  try {
    const body = { bankCode: form.bankCode, accountNumber: number.normalized, accountHolder: form.accountHolder.trim() }
    if (props.target) {
      await sellersApi.updateBankAccount(props.detail.sellerPublicId, props.target.id, { ...body, reason: reason.value.trim() })
      toast.info(`${bankLabel(form.bankCode)} 계좌 정보를 수정했습니다.`)
    } else {
      const created = await sellersApi.registerBankAccount(props.detail.sellerPublicId, body)
      toast.success(created.isPrimary
        ? `${bankLabel(created.bankCode)} ${maskedAccountNumber(created)} 계좌를 등록하고 주 정산계좌로 지정했습니다.`
        : `${bankLabel(created.bankCode)} ${maskedAccountNumber(created)} 계좌를 등록했습니다.`)
    }
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { accountNumber: toAdminErrorMessage(error) }
    } else if (code === 'SELLER_BANK_ACCOUNT_REFERENCED' || code === 'SELLER_BANK_ACCOUNT_NOT_FOUND' || code === 'SELLER_NOT_FOUND') {
      // 정산 참조(409)는 화면이 미리 알 수 없고(응답 플래그 없음), 404는 화면이 오래된 경우 → 부모가 다시 읽는다.
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
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-bank-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ isEdit ? '정산계좌 수정' : '정산계좌 등록' }}</v-card-title>
      <v-card-text class="px-5">
        <v-alert v-if="isFirstAccount" type="info" variant="tonal" density="compact" :icon="mdiInformationOutline" class="mb-4" data-testid="seller-bank-first-notice">
          {{ SELLER_BANK_ACCOUNT_FIRST_PRIMARY_NOTICE }}
        </v-alert>
        <v-alert v-else-if="isEdit && lastTarget" type="info" variant="tonal" density="compact" :icon="mdiInformationOutline" class="mb-4" data-testid="seller-bank-edit-notice">
          현재 계좌 {{ bankLabel(lastTarget.bankCode) }} {{ maskedAccountNumber(lastTarget) }} ({{ lastTarget.accountHolder }}) — 보안상 기존 번호는 표시되지 않습니다. 계좌번호를 다시 입력하세요.
        </v-alert>
        <v-select
          v-model="form.bankCode"
          :items="ADMIN_BANK_OPTIONS"
          label="은행 *"
          :error-messages="errors.bankCode ? [errors.bankCode] : []"
          :disabled="submitting"
          data-testid="seller-bank-code"
          @update:model-value="clearError('bankCode')"
        />
        <v-text-field
          v-model="form.accountNumber"
          label="계좌번호 *"
          placeholder="숫자와 하이픈만 (예: 110-000-000000)"
          inputmode="numeric"
          :maxlength="ADMIN_SELLER_ACCOUNT_NUMBER_MAX"
          hint="입력 중에는 마스킹하지 않습니다. 오타가 없는지 확인한 뒤 저장하세요."
          persistent-hint
          :error-messages="errors.accountNumber ? [errors.accountNumber] : []"
          :disabled="submitting"
          autofocus
          data-testid="seller-bank-number-input"
          @update:model-value="clearError('accountNumber')"
        />
        <v-text-field
          v-model="form.accountHolder"
          label="예금주 *"
          :maxlength="ADMIN_SELLER_ACCOUNT_HOLDER_MAX"
          :error-messages="errors.accountHolder ? [errors.accountHolder] : []"
          :disabled="submitting"
          class="mt-2"
          data-testid="seller-bank-holder"
          @update:model-value="clearError('accountHolder')"
        />
        <v-textarea
          v-if="isEdit"
          v-model="reason"
          label="수정 사유 (필수)"
          placeholder="예: 셀러 요청으로 계좌번호 오기 정정(2026-09-18 접수)"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          class="mt-2"
          data-testid="seller-bank-reason"
          @update:model-value="clearError('reason')"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-bank-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-bank-ok" @click="submit">
          {{ isEdit ? '수정' : '등록' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
