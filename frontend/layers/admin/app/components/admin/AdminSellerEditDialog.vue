<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_BUSINESS_NO_MAX,
  ADMIN_SELLER_CEO_NAME_MAX,
  ADMIN_SELLER_COMPANY_NAME_MAX,
  ADMIN_SELLER_CONTACT_EMAIL_MAX,
  ADMIN_SELLER_CONTACT_PHONE_MAX,
  ADMIN_SELLER_REASON_MAX,
  SELLER_COMMISSION_RATE_CHANGE_WARNING,
} from '#layers/admin/app/lib/constants/admin-seller'
import { commissionRateChanged, parsePercentInput, toPercentInput } from '#layers/admin/app/lib/admin-category-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 셀러 정보 수정 다이얼로그(FE-40·BE D-187 PUT 전체 필드·AdminCategoryEditDialog 패턴). 상호·사업자번호·대표자·연락처·수수료율(%·빈 값 = 미설정)을
 * 받고 율이 원래 값과 달라지면 사유가 필수·경고 문구를 강조한다(D-187 §1-A 8·율은 주문 생성 시 스냅샷·셀러율이 카테고리율보다 우선).
 * PUT은 204라 부모가 done 시 상세를 다시 읽는다. 400은 fieldErrors, 사유 필수(MALFORMED_REQUEST)는 사유 필드, 409 사업자번호 중복은 사업자번호 필드.
 */
const props = defineProps<{ open: boolean; detail: AdminSellerDetail | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const form = reactive({ companyName: '', businessNo: '', ceoName: '', contactEmail: '', contactPhone: '' })
const percentInput = ref('')
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  Object.assign(form, {
    companyName: props.detail?.companyName ?? '',
    businessNo: props.detail?.businessNo ?? '',
    ceoName: props.detail?.ceoName ?? '',
    contactEmail: props.detail?.contactEmail ?? '',
    contactPhone: props.detail?.contactPhone ?? '',
  })
  percentInput.value = toPercentInput(props.detail?.commissionRate)
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

const parsedPercent = computed(() => parsePercentInput(percentInput.value))
const rateChanged = computed(() => {
  const parsed = parsedPercent.value
  return parsed.ok && commissionRateChanged(props.detail?.commissionRate, parsed.basisPoints)
})
const confirmDisabled = computed(() =>
  submitting.value || form.companyName.trim() === '' || form.ceoName.trim() === '' || (rateChanged.value && reason.value.trim() === ''),
)

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  const localErrors: Record<string, string> = {}
  if (form.companyName.trim() === '') localErrors.companyName = '상호명을 입력하세요.'
  if (form.companyName.trim().length > ADMIN_SELLER_COMPANY_NAME_MAX) localErrors.companyName = `상호명은 ${ADMIN_SELLER_COMPANY_NAME_MAX}자 이하여야 합니다.`
  if (form.ceoName.trim() === '') localErrors.ceoName = '대표자명을 입력하세요.'
  const parsed = parsedPercent.value
  if (!parsed.ok) localErrors.commissionRate = parsed.message
  if (rateChanged.value && reason.value.trim() === '') localErrors.reason = '수수료율을 변경하려면 사유를 입력하세요.'
  if (Object.keys(localErrors).length > 0) {
    errors.value = localErrors
    return
  }
  submitting.value = true
  try {
    await sellersApi.update(props.detail.sellerPublicId, {
      companyName: form.companyName.trim(),
      businessNo: blankToNull(form.businessNo),
      ceoName: form.ceoName.trim(),
      contactEmail: blankToNull(form.contactEmail),
      contactPhone: blankToNull(form.contactPhone),
      commissionRate: parsed.ok ? parsed.basisPoints : null,
      reason: blankToNull(reason.value),
    })
    toast.info(rateChanged.value ? '셀러 정보와 수수료율을 수정했습니다. 새로 생성되는 주문부터 적용됩니다.' : '셀러 정보를 수정했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { companyName: toAdminErrorMessage(error) }
    } else if (code === 'MALFORMED_REQUEST') {
      errors.value = { reason: '수수료율을 변경하려면 사유를 입력하세요.' }
    } else if (code === 'SELLER_BUSINESS_NO_DUPLICATE') {
      errors.value = { businessNo: toAdminErrorMessage(error) }
    } else if (code === 'SELLER_NOT_FOUND') {
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
  <v-dialog :model-value="open" max-width="640" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-edit-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">셀러 정보 수정</v-card-title>
      <v-card-text class="px-5">
        <v-row dense>
          <v-col cols="12" md="7">
            <v-text-field v-model="form.companyName" label="상호명 *" :maxlength="ADMIN_SELLER_COMPANY_NAME_MAX" :error-messages="errors.companyName ? [errors.companyName] : []" :disabled="submitting" autofocus data-testid="seller-edit-company" @update:model-value="clearError('companyName')" />
          </v-col>
          <v-col cols="12" md="5">
            <v-text-field v-model="form.ceoName" label="대표자명 *" :maxlength="ADMIN_SELLER_CEO_NAME_MAX" :error-messages="errors.ceoName ? [errors.ceoName] : []" :disabled="submitting" data-testid="seller-edit-ceo" @update:model-value="clearError('ceoName')" />
          </v-col>
          <v-col cols="12" md="5">
            <v-text-field v-model="form.businessNo" label="사업자등록번호" :maxlength="ADMIN_SELLER_BUSINESS_NO_MAX" :error-messages="errors.businessNo ? [errors.businessNo] : []" :disabled="submitting" data-testid="seller-edit-business-no" @update:model-value="clearError('businessNo')" />
          </v-col>
          <v-col cols="12" md="7">
            <v-text-field v-model="form.contactEmail" label="담당자 이메일" type="email" :maxlength="ADMIN_SELLER_CONTACT_EMAIL_MAX" :error-messages="errors.contactEmail ? [errors.contactEmail] : []" :disabled="submitting" data-testid="seller-edit-email" @update:model-value="clearError('contactEmail')" />
          </v-col>
          <v-col cols="12" md="5">
            <v-text-field v-model="form.contactPhone" label="담당자 연락처" :maxlength="ADMIN_SELLER_CONTACT_PHONE_MAX" :error-messages="errors.contactPhone ? [errors.contactPhone] : []" :disabled="submitting" data-testid="seller-edit-phone" @update:model-value="clearError('contactPhone')" />
          </v-col>
          <v-col cols="12" md="7">
            <v-text-field
              v-model="percentInput"
              label="셀러 개별 수수료율 (%)"
              placeholder="예: 5 또는 5.25"
              inputmode="decimal"
              suffix="%"
              hint="빈 값 = 미설정(카테고리율 → 플랫폼 기본율 적용)"
              persistent-hint
              :error-messages="errors.commissionRate ? [errors.commissionRate] : []"
              :disabled="submitting"
              clearable
              data-testid="seller-edit-rate"
              @update:model-value="clearError('commissionRate')"
            />
          </v-col>
        </v-row>
        <v-alert :type="rateChanged ? 'warning' : 'info'" variant="tonal" density="compact" :icon="mdiAlertOutline" class="my-3" data-testid="seller-edit-rate-warning">
          <p v-for="line in SELLER_COMMISSION_RATE_CHANGE_WARNING" :key="line" class="text-body-2 mb-0">{{ line }}</p>
        </v-alert>
        <v-textarea
          v-model="reason"
          :label="rateChanged ? '변경 사유 (필수)' : '변경 사유'"
          placeholder="예: 2026년 4분기 셀러 수수료 계약 반영"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="seller-edit-reason"
          @update:model-value="clearError('reason')"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-edit-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-edit-ok" @click="submit">수정</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
