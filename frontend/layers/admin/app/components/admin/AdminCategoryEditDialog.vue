<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminCategorySummary } from '#layers/admin/app/types/admin-category'
import {
  ADMIN_CATEGORY_NAME_MAX,
  ADMIN_CATEGORY_REASON_MAX,
  COMMISSION_RATE_CHANGE_WARNING,
} from '#layers/admin/app/lib/constants/admin-category'
import {
  commissionRateChanged,
  formatPercent,
  parsePercentInput,
  toPercentInput,
} from '#layers/admin/app/lib/admin-category-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminCategories } from '#layers/admin/app/composables/useAdminCategories'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 카테고리 등록·편집 다이얼로그(FE-38·AdminMemberEditDialog 패턴). 등록(item 없음)은 기존 생성 API(이름·sortOrder=목록 끝)만 쓴다.
 * 편집은 PUT 전체 치환 — 이름·수수료율(%·빈 값 = 미설정)을 받고 sortOrder는 현재 값을 그대로 보낸다(순서는 표의 위/아래 버튼).
 * 수수료율이 원래 값과 달라지면 사유가 필수이고 경고 문구(D-185)를 강조한다. 400은 fieldErrors, 409 중복은 이름 필드 오류로 표시한다.
 */
const props = defineProps<{
  open: boolean
  item: AdminCategorySummary | null
  defaultCommissionRate: number
  /** 등록 시 sortOrder(목록 끝 = 현재 건수). */
  nextSortOrder: number
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const categoriesApi = useAdminCategories()
const toast = useAdminToast()

const isEdit = computed(() => props.item !== null)
const displayName = ref('')
const percentInput = ref('')
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  displayName.value = props.item?.displayName ?? ''
  percentInput.value = toPercentInput(props.item?.commissionRate)
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
  return isEdit.value && parsed.ok && commissionRateChanged(props.item?.commissionRate, parsed.basisPoints)
})
const rateHint = computed(() =>
  `빈 값 = 미설정(기본율 ${formatPercent(props.defaultCommissionRate)} 적용). 셀러 개별율이 없을 때 이 율이 적용됩니다.`,
)

const confirmDisabled = computed(() =>
  submitting.value || displayName.value.trim() === '' || (rateChanged.value && reason.value.trim() === ''),
)

async function submit(): Promise<void> {
  if (submitting.value) return
  const name = displayName.value.trim()
  const localErrors: Record<string, string> = {}
  if (name === '') localErrors.displayName = '카테고리명을 입력하세요.'
  if (name.length > ADMIN_CATEGORY_NAME_MAX) localErrors.displayName = `카테고리명은 ${ADMIN_CATEGORY_NAME_MAX}자 이하여야 합니다.`
  const parsed = parsedPercent.value
  if (isEdit.value && !parsed.ok) localErrors.commissionRate = parsed.message
  if (rateChanged.value && reason.value.trim() === '') localErrors.reason = '수수료율을 변경하려면 사유를 입력하세요.'
  if (Object.keys(localErrors).length > 0) {
    errors.value = localErrors
    return
  }

  submitting.value = true
  try {
    if (props.item === null) {
      await categoriesApi.create({ displayName: name, sortOrder: props.nextSortOrder })
      toast.success('카테고리를 등록했습니다.')
    } else {
      await categoriesApi.update(props.item.categoryId, {
        displayName: name,
        sortOrder: props.item.sortOrder,
        commissionRate: parsed.ok ? parsed.basisPoints : null,
        reason: reason.value.trim() === '' ? null : reason.value.trim(),
      })
      toast.info(rateChanged.value ? '카테고리와 수수료율을 수정했습니다. 새로 생성되는 주문부터 적용됩니다.' : '카테고리를 수정했습니다.')
    }
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { displayName: toAdminErrorMessage(error) }
    } else if (code === 'MALFORMED_REQUEST') {
      // BE 사유 필수 판정(율 실변경)은 fieldErrors 없이 400이라 사유 필드에 붙인다.
      errors.value = { reason: '수수료율을 변경하려면 사유를 입력하세요.' }
    } else if (code === 'CATEGORY_DUPLICATE') {
      errors.value = { displayName: toAdminErrorMessage(error) }
    } else if (code === 'CATEGORY_NOT_FOUND') {
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
    <v-card data-testid="admin-category-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ isEdit ? '카테고리 수정' : '카테고리 등록' }}</v-card-title>
      <v-card-text class="px-5">
        <v-text-field
          v-model="displayName"
          label="카테고리명"
          :maxlength="ADMIN_CATEGORY_NAME_MAX"
          :error-messages="errors.displayName ? [errors.displayName] : []"
          :disabled="submitting"
          autofocus
          data-testid="category-name"
          @update:model-value="clearError('displayName')"
        />
        <template v-if="isEdit">
          <v-text-field
            v-model="percentInput"
            label="수수료율 (%)"
            placeholder="예: 5 또는 5.25"
            inputmode="decimal"
            suffix="%"
            :hint="rateHint"
            persistent-hint
            :error-messages="errors.commissionRate ? [errors.commissionRate] : []"
            :disabled="submitting"
            clearable
            data-testid="category-rate"
            @update:model-value="clearError('commissionRate')"
          />
          <v-alert
            :type="rateChanged ? 'warning' : 'info'"
            variant="tonal"
            density="compact"
            :icon="mdiAlertOutline"
            class="my-3"
            data-testid="category-rate-warning"
          >
            <p v-for="line in COMMISSION_RATE_CHANGE_WARNING" :key="line" class="text-body-2 mb-0">{{ line }}</p>
          </v-alert>
          <v-textarea
            v-model="reason"
            :label="rateChanged ? '변경 사유 (필수)' : '변경 사유'"
            placeholder="예: 2026년 4분기 카테고리 수수료 계약 반영"
            rows="2"
            auto-grow
            :maxlength="ADMIN_CATEGORY_REASON_MAX"
            :counter="ADMIN_CATEGORY_REASON_MAX"
            :error-messages="errors.reason ? [errors.reason] : []"
            :disabled="submitting"
            data-testid="category-reason"
            @update:model-value="clearError('reason')"
          />
        </template>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="category-dialog-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="category-dialog-ok" @click="submit">
          {{ isEdit ? '수정' : '등록' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
