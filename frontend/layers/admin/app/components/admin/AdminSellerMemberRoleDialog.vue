<script setup lang="ts">
import type { AdminSellerDetail, AdminSellerMember } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_MEMBER_ROLE_LABEL,
  ADMIN_SELLER_REASON_MAX,
  SELLER_MEMBER_ROLE_NOTICE,
  type AdminSellerMemberRole,
} from '#layers/admin/app/lib/constants/admin-seller'
import { memberDisplayName } from '#layers/admin/app/lib/admin-seller-view'
import { memberRoleChip, selectableRoles, toSellerMemberErrorMessage } from '#layers/admin/app/lib/admin-seller-member-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 구성원 역할 변경 다이얼로그(FE-42·D-189 PATCH …/members/{usr_}/role 204·사유 필수). 선택지에서 현재 역할을 제외해 같은 역할 재요청(BE 422)을
 * 화면에서 막는다. 마지막 활성 대표 강등은 카드가 버튼을 비활성 처리하며(제거와 같은 판정), 놓친 경우 409 SELLER_LAST_OWNER를 토스트 후 stale.
 * 역할이 권한에 영향을 주지 않는다는 안내를 항상 표시한다.
 */
const props = defineProps<{ open: boolean; detail: AdminSellerDetail | null; target: AdminSellerMember | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const role = ref<AdminSellerMemberRole | null>(null)
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const lastTarget = ref<AdminSellerMember | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  role.value = null
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const targetLabel = computed(() => (lastTarget.value ? memberDisplayName(lastTarget.value) : ''))
const currentRoleLabel = computed(() => memberRoleChip(lastTarget.value?.roleCode).text)
const roleOptions = computed(() => selectableRoles(lastTarget.value?.roleCode).map((value) => ({ value, title: ADMIN_SELLER_MEMBER_ROLE_LABEL[value] })))
const confirmDisabled = computed(() => submitting.value || role.value === null || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.detail || !props.target?.userPublicId || role.value === null) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await sellersApi.changeMemberRole(props.detail.sellerPublicId, props.target.userPublicId, { role: role.value, reason: trimmed })
    toast.success(`${targetLabel.value}의 역할을 ${currentRoleLabel.value} → ${ADMIN_SELLER_MEMBER_ROLE_LABEL[role.value]}(으)로 변경했습니다.`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else {
      // 422 SELLER_MEMBER_INVALID_STATE(같은 역할)·409 SELLER_LAST_OWNER·404 → 화면이 오래됐을 수 있어 부모가 다시 읽는다.
      toast.warning(toSellerMemberErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-member-role-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">역할 변경</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 font-weight-medium mb-3" data-testid="seller-member-role-headline">
          {{ targetLabel }}의 역할을 변경합니다. 현재 역할: {{ currentRoleLabel }}
        </p>
        <v-select v-model="role" :items="roleOptions" label="새 역할" :disabled="submitting" data-testid="seller-member-role-select" />
        <p class="text-caption text-medium-emphasis mb-3" data-testid="seller-member-role-notice">
          <template v-for="(line, index) in SELLER_MEMBER_ROLE_NOTICE" :key="line">{{ line }}<br v-if="index < SELLER_MEMBER_ROLE_NOTICE.length - 1"></template>
        </p>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          placeholder="예: 대표 교체(2026-09-19 셀러 요청)"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="seller-member-role-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-member-role-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-member-role-ok" @click="submit">역할 변경</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
