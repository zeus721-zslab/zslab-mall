<script setup lang="ts">
import { mdiAlertOutline } from '@mdi/js'
import { ADMIN_PRODUCT_REASON_MAX } from '#layers/admin/app/lib/constants/product'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 상품 거부 철회 다이얼로그(Track 101-A·BE POST /withdraw-rejection·AdminSellerStatusDialog 패턴). 거부(REJECTED)를 되돌려
 * 승인대기(PENDING)로 보낸다. 사유는 필수이며 감사 로그에만 남는다(Product 컬럼 없음).
 * 성공 시 done(전이 후 상태) → 부모가 행·상단 표시를 갱신한다. 422(REJECTED 아님)·404는 warning 후 stale(부모 재조회).
 */
const props = defineProps<{
  open: boolean
  productPublicId: string | null
  productName: string
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const productsApi = useAdminProducts()
const toast = useAdminToast()

const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 문구가 사라지지 않도록 마지막 상품명을 유지한다(AdminSellerStatusDialog 패턴).
const lastName = ref('')
watch(() => props.productName, (next) => { if (next) lastName.value = next })

function reset(): void {
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

const confirmDisabled = computed(() => submitting.value || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.productPublicId) return
  const trimmed = reason.value.trim()
  if (trimmed === '') {
    errors.value = { reason: '철회 사유를 입력하세요.' }
    return
  }
  submitting.value = true
  try {
    await productsApi.withdrawRejection(props.productPublicId, trimmed)
    toast.info(`${lastName.value} 거부를 철회했습니다. 승인대기로 돌아갑니다.`) // 상태 복귀는 중립
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) errors.value = { reason: toAdminErrorMessage(error) }
    } else {
      // 422 PRODUCT_INVALID_STATE(이미 철회됨·거부 상태 아님)·404 → 화면이 오래됐을 수 있어 부모가 다시 읽는다.
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
    <v-card data-testid="admin-product-withdraw-rejection-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">거부 철회</v-card-title>
      <v-card-text class="px-5">
        <v-alert
          type="info"
          variant="tonal"
          density="compact"
          :icon="mdiAlertOutline"
          class="mb-4"
          data-testid="withdraw-rejection-message"
        >
          <p class="text-body-2 mb-0">{{ lastName }}의 거부를 철회해 승인대기로 되돌립니다.</p>
          <p class="text-body-2 mb-0">철회 후에는 다시 승인하거나 거부할 수 있고, 셀러도 수정해 재요청할 수 있습니다.</p>
        </v-alert>
        <v-textarea
          v-model="reason"
          label="철회 사유 (필수)"
          placeholder="예: 거부 기준 오적용 — 카테고리 확인 후 재심사"
          rows="2"
          auto-grow
          :maxlength="ADMIN_PRODUCT_REASON_MAX"
          :counter="ADMIN_PRODUCT_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          autofocus
          data-testid="withdraw-rejection-reason"
          @update:model-value="errors = { ...errors, reason: '' }"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="withdraw-rejection-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="withdraw-rejection-ok" @click="submit">
          거부 철회
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
