<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import type { AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_REASON_MAX,
  ADMIN_SELLER_TRANSITION_LABEL,
  type AdminSellerStatus,
} from '#layers/admin/app/lib/constants/admin-seller'
import { toSellerErrorMessage, transitionConfirmMessage } from '#layers/admin/app/lib/admin-seller-view'
import { extractErrorCode } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 셀러 상태 전이 확인 다이얼로그(FE-40·BE D-187 PATCH /status·AdminOperatorRevokeDialog 패턴). 목표 상태별 확인 문구(종료는 불가역 안내 필수)와
 * 사유(필수·감사 이력·종료 아카이브)를 받아 PATCH한다. 성공 응답은 전이 후 상세라 부모가 재조회 없이 그대로 반영한다(done payload).
 * 409(가드 위반·blocks)·422(불법 전이)·404는 토스트 후 stale로 부모가 다시 읽는다(화면 판정과 서버가 어긋난 경우).
 */
const props = defineProps<{
  open: boolean
  detail: AdminSellerDetail | null
  target: Exclude<AdminSellerStatus, 'PENDING'> | null
}>()
const emit = defineEmits<{ done: [detail: AdminSellerDetail]; stale: []; cancel: [] }>()

const sellersApi = useAdminSellers()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 문구가 사라지지 않도록 마지막 대상을 유지한다.
const lastTarget = ref<Exclude<AdminSellerStatus, 'PENDING'> | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const isTerminate = computed(() => lastTarget.value === 'TERMINATED')
const title = computed(() => (lastTarget.value ? `셀러 ${ADMIN_SELLER_TRANSITION_LABEL[lastTarget.value]}` : ''))
const message = computed(() => (props.detail && lastTarget.value ? transitionConfirmMessage(props.detail, lastTarget.value) : ''))
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
    const updated = await sellersApi.changeStatus(props.detail.sellerPublicId, { status: props.target, reason: trimmed })
    if (props.target === 'TERMINATED') toast.danger(`${props.detail.companyName} 셀러를 종료했습니다.`)
    else if (props.target === 'SUSPENDED') toast.warning(`${props.detail.companyName} 셀러를 정지했습니다. 상품이 카탈로그에서 즉시 숨겨집니다.`)
    else toast.success(`${props.detail.companyName} 셀러를 활성화했습니다.`)
    emit('done', updated)
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toSellerErrorMessage(error))
    } else {
      // 409 SELLER_ACTIVITY_IN_PROGRESS(가드·건수 조립)·422 SELLER_INVALID_STATE·404 → 화면이 오래됐을 수 있어 부모가 다시 읽는다.
      toast.warning(toSellerErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="560" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-status-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">{{ title }}</v-card-title>
      <v-card-text class="px-5">
        <v-alert
          :type="isTerminate ? 'error' : 'warning'"
          variant="tonal"
          density="compact"
          :icon="mdiAlertOutline"
          class="mb-4"
          data-testid="seller-status-message"
        >
          <p v-for="line in message.split('\n')" :key="line" class="text-body-2 mb-0" :class="{ 'font-weight-bold': isTerminate && line.startsWith('종료 후에는') }">{{ line }}</p>
        </v-alert>
        <v-textarea
          v-model="reason"
          label="사유 (필수)"
          :placeholder="isTerminate ? '예: 판매자 탈퇴 요청(2026-09-18 접수)' : '예: 정책 위반 신고 3건 확인'"
          rows="2"
          auto-grow
          :maxlength="ADMIN_SELLER_REASON_MAX"
          :counter="ADMIN_SELLER_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="seller-status-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-status-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn variant="flat" class="op-risk-action" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-status-ok" @click="submit">
          {{ lastTarget ? ADMIN_SELLER_TRANSITION_LABEL[lastTarget] : '확인' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
