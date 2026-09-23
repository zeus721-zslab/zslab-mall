<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminSellerDetail, AdminSellerMember } from '#layers/admin/app/types/admin-seller'
import { ADMIN_SELLER_REASON_MAX, SELLER_MEMBER_REMOVE_NOTICE } from '#layers/admin/app/lib/constants/admin-seller'
import { memberDisplayName } from '#layers/admin/app/lib/admin-seller-view'
import { memberRoleChip, toSellerMemberErrorMessage } from '#layers/admin/app/lib/admin-seller-member-view'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 구성원 제거 확인 다이얼로그(FE-42·D-189 DELETE …/members/{usr_} 204·사유 필수·AdminOperatorRevokeDialog 선례). 즉시 차단·구매자 계정 유지 안내를
 * 담는다. 탈퇴 회원 행 제거는 정리 성격이라 문구를 분기한다. 409 SELLER_LAST_OWNER(화면 미리보기가 놓친 경우)·404는 토스트 후 stale.
 */
const props = defineProps<{ open: boolean; detail: AdminSellerDetail | null; target: AdminSellerMember | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const lastTarget = ref<AdminSellerMember | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const targetLabel = computed(() => (lastTarget.value ? memberDisplayName(lastTarget.value) : ''))
const targetRole = computed(() => memberRoleChip(lastTarget.value?.roleCode).text)
const withdrawnTarget = computed(() => lastTarget.value?.withdrawnAt !== undefined)
const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.detail || !props.target?.userPublicId) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await sellersApi.removeMember(props.detail.sellerPublicId, props.target.userPublicId, { reason: trimmed })
    toast.success(withdrawnTarget.value
      ? `탈퇴 회원 ${targetLabel.value}의 구성원 행을 제거했습니다.`
      : `${targetLabel.value} 구성원을 제거했습니다. 이 계정의 셀러 접근은 즉시 차단됩니다.`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else {
      // 409 SELLER_LAST_OWNER·404 SELLER_MEMBER_NOT_FOUND → 화면이 오래됐을 수 있어 부모가 다시 읽는다.
      toast.warning(toSellerMemberErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-member-remove-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">구성원 제거</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 font-weight-medium mb-3" data-testid="seller-member-remove-headline">
          {{ detail?.companyName }} 셀러에서 {{ targetLabel }}({{ targetRole }}) 구성원을 제거합니다.
        </p>
        <v-alert v-if="withdrawnTarget" type="info" variant="tonal" density="compact" class="mb-4" data-testid="seller-member-remove-withdrawn-notice">
          탈퇴한 회원의 구성원 행을 정리합니다. 탈퇴 회원은 이미 로그인할 수 없으므로 접근 변화는 없으며, 구성원 목록에서만 사라집니다.
        </v-alert>
        <v-alert v-else type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="seller-member-remove-notice">
          <p v-for="line in SELLER_MEMBER_REMOVE_NOTICE" :key="line" class="text-body-2 mb-0" :class="{ 'font-weight-bold': line === SELLER_MEMBER_REMOVE_NOTICE[0] }">{{ line }}</p>
        </v-alert>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          placeholder="예: 퇴사(2026-09-19 셀러 요청)"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="seller-member-remove-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-member-remove-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-member-remove-ok" @click="submit">제거</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
