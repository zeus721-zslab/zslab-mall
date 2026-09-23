<script setup lang="ts">
import type { AdminOperatorSummary } from '#layers/admin/app/types/admin-operator'
import {
  ADMIN_OPERATOR_REVOKE_REASON_MAX,
  ADMIN_OPERATOR_ROLE_LABEL,
  type AdminOperatorRole,
} from '#layers/admin/app/lib/constants/admin-operator'
import { revokeConfirmMessage, roleRevokeBlockedReason, toOperatorErrorMessage } from '#layers/admin/app/lib/admin-operator-view'
import { extractErrorCode } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminOperators } from '#layers/admin/app/composables/useAdminOperators'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 역할 회수 다이얼로그(FE-39·BE D-186). 대상 행의 ADMIN 계열 역할 중 하나를 골라 사유(필수·감사 로그)와 함께 DELETE한다. 마지막 SUPER_ADMIN은
 * 인원수를 알 때만 선택지를 미리 막고, 모르면 서버 409(LAST_SUPER_ADMIN)를 토스트로 보여준다. 확인 문구는 역할별로 재부여 가능 여부를 명시한다.
 * 호출·토스트는 다이얼로그가 소유하고 부모는 done 시 다시 읽는다(AdminPaymentCancelDialog 패턴).
 */
const props = defineProps<{
  open: boolean
  target: AdminOperatorSummary | null
  /** 현재 목록에서 센 SUPER_ADMIN 인원(모르면 null → 서버 판정에 맡김). */
  superAdminCount: number | null
}>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const operatorsApi = useAdminOperators()
const toast = useAdminToast()

const role = ref<AdminOperatorRole | null>(null)
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 대상이 사라지지 않도록 마지막 대상을 유지한다(target은 즉시 null).
const lastTarget = ref<AdminOperatorSummary | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

const roleOptions = computed(() =>
  (lastTarget.value?.roles ?? []).map((held) => ({
    value: held,
    title: ADMIN_OPERATOR_ROLE_LABEL[held],
    blocked: roleRevokeBlockedReason(held, props.superAdminCount),
  })),
)

function reset(): void {
  const selectable = roleOptions.value.find((option) => option.blocked === null)
  role.value = selectable ? selectable.value : null
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const message = computed(() => (lastTarget.value && role.value ? revokeConfirmMessage(lastTarget.value, role.value) : ''))
const confirmDisabled = computed(() => submitting.value || role.value === null || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.target || !role.value) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await operatorsApi.revoke(props.target.userPublicId, role.value, { reason: trimmed })
    toast.danger(`${ADMIN_OPERATOR_ROLE_LABEL[role.value]} 역할을 회수했습니다.`) // 권한 상실은 부정적 의미
    emit('done')
  } catch (error) {
    if (extractErrorCode(error) === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toOperatorErrorMessage(error))
    } else {
      // 403(SUPER_ADMIN 아님·자기 회수)·409(마지막 SUPER_ADMIN)·404(이미 회수)는 구체 문구로 알리고 부모가 목록을 되돌린다.
      toast.danger(toOperatorErrorMessage(error))
      emit('done')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-operator-revoke-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">역할 회수</v-card-title>
      <v-card-text class="px-5">
        <v-radio-group v-model="role" label="회수할 역할" :disabled="submitting" hide-details class="mb-3" data-testid="revoke-role">
          <v-radio
            v-for="option in roleOptions"
            :key="option.value"
            :value="option.value"
            :disabled="option.blocked !== null"
            :data-testid="`revoke-role-${option.value}`"
          >
            <template #label>
              <span>{{ option.title }}</span>
              <span v-if="option.blocked" class="text-caption text-medium-emphasis ml-2" data-testid="revoke-role-blocked">{{ option.blocked }}</span>
            </template>
          </v-radio>
        </v-radio-group>
        <p v-if="message" class="text-body-2 mb-3" style="white-space: pre-line" data-testid="revoke-message">{{ message }}</p>
        <v-textarea
          v-model="reason"
          label="사유"
          placeholder="예: 퇴사 처리 / 담당 변경"
          rows="2"
          auto-grow
          :maxlength="ADMIN_OPERATOR_REVOKE_REASON_MAX"
          :counter="ADMIN_OPERATOR_REVOKE_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="revoke-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="revoke-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="revoke-dialog-ok" @click="submit">
          회수
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
