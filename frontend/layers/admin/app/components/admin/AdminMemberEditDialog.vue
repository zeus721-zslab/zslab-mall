<script setup lang="ts">
import type { AdminMemberDetail } from '#layers/admin/app/types/admin-member'
import { ADMIN_MEMBER_NAME_MAX, ADMIN_MEMBER_PHONE_MAX } from '#layers/admin/app/lib/constants/admin-member'
import { validateMemberForm } from '#layers/admin/app/lib/admin-member-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 회원 정보 수정 다이얼로그(Track 84 FE·AdminShipmentDialog 패턴). name(필수·≤50)·phone(필수·휴대폰 형식). 성공 시 info 토스트 후 done(부모 재조회),
 * 400은 fieldErrors 표시, 409(탈퇴 회원)는 warning 토스트 후 stale(부모 재조회).
 */
const props = defineProps<{
  open: boolean
  detail: AdminMemberDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const membersApi = useAdminMembers()
const toast = useAdminToast()

const name = ref('')
const phone = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  name.value = props.detail?.name ?? ''
  phone.value = props.detail?.phone ?? ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  const validation = validateMemberForm({ name: name.value, phone: phone.value })
  errors.value = validation
  if (Object.keys(validation).length > 0) return

  submitting.value = true
  try {
    await membersApi.update(props.detail.publicId, { name: name.value.trim(), phone: phone.value.trim() })
    toast.info('회원 정보를 수정했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { phone: toAdminErrorMessage(error) }
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
    <v-card data-testid="admin-member-edit-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">회원 정보 수정</v-card-title>
      <v-card-text class="px-5">
        <v-text-field
          v-model="name"
          label="이름"
          :maxlength="ADMIN_MEMBER_NAME_MAX"
          :counter="ADMIN_MEMBER_NAME_MAX"
          :error-messages="errors.name ? [errors.name] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="member-edit-name"
          @update:model-value="clearError('name')"
        />
        <v-text-field
          v-model="phone"
          label="연락처"
          placeholder="010-1234-5678"
          :maxlength="ADMIN_MEMBER_PHONE_MAX"
          :error-messages="errors.phone ? [errors.phone] : []"
          :disabled="submitting"
          data-testid="member-edit-phone"
          @update:model-value="clearError('phone')"
          @keyup.enter="submit"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="member-edit-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting" data-testid="member-edit-ok" @click="submit">저장</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
