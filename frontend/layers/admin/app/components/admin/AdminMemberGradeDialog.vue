<script setup lang="ts">
import type { AdminMemberDetail } from '#layers/admin/app/types/admin-member'
import { ADMIN_BUYER_GRADE_OPTIONS, type AdminBuyerGradeCode } from '#layers/admin/app/lib/constants/admin-member'
import { minLockedUntil, validateGradeForm } from '#layers/admin/app/lib/admin-member-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 수동 등급 변경 다이얼로그(Track 84 FE). 등급(SILVER·GOLD·PLATINUM) + 유지 기한(네이티브 date·min=내일). 성공 시 info 토스트 후 done(부모 재조회),
 * 400은 fieldErrors 표시, 409(탈퇴 회원)는 warning 토스트 후 stale.
 */
const props = defineProps<{
  open: boolean
  detail: AdminMemberDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const membersApi = useAdminMembers()
const toast = useAdminToast()

const gradeCode = ref<AdminBuyerGradeCode | null>(null)
const lockedUntil = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
const minDate = computed(() => minLockedUntil())

function reset(): void {
  gradeCode.value = props.detail?.grade?.code ?? null
  lockedUntil.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  const validation = validateGradeForm({ gradeCode: gradeCode.value, lockedUntil: lockedUntil.value })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !gradeCode.value) return

  submitting.value = true
  try {
    await membersApi.changeGrade(props.detail.publicId, { gradeCode: gradeCode.value, lockedUntil: lockedUntil.value })
    toast.info('등급을 변경했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { lockedUntil: toAdminErrorMessage(error) }
    } else if (code === 'MEMBER_ALREADY_WITHDRAWN' || code === 'USER_NOT_FOUND') {
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
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-member-grade-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">등급 변경</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 text-medium-emphasis mb-3">수동으로 부여한 등급은 유지 기한까지 자동 재산정에서 제외됩니다.</p>
        <v-select
          :model-value="gradeCode"
          :items="ADMIN_BUYER_GRADE_OPTIONS"
          label="등급"
          :error-messages="errors.gradeCode ? [errors.gradeCode] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="member-grade-select"
          @update:model-value="(value) => { gradeCode = value ?? null; clearError('gradeCode') }"
        />
        <v-text-field
          v-model="lockedUntil"
          type="date"
          label="유지 기한"
          :min="minDate"
          :error-messages="errors.lockedUntil ? [errors.lockedUntil] : []"
          :disabled="submitting"
          data-testid="member-grade-locked-until"
          @update:model-value="clearError('lockedUntil')"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="member-grade-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting" data-testid="member-grade-ok" @click="submit">변경</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
