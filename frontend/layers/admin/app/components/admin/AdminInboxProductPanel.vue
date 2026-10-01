<script setup lang="ts">
import type { AdminProductDetail } from '#layers/admin/app/types/admin-product'
import type { AdminProductStatusTarget } from '#layers/admin/app/lib/constants/product'
import { ADMIN_PRODUCT_STATUS_LABEL } from '#layers/admin/app/lib/constants/product'
import { rejectConfirmMessage } from '#layers/admin/app/lib/admin-product-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatWon } from '#layers/admin/app/lib/format'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 인박스 상품 승인 대기 패널(D-251 · 상세 패널 안). 상품 단건 조회로 셀러·카테고리·가격·옵션 수를 보이고 승인대기면 승인·거부한다. 승인은 상품 목록과 같이
 * 확인 없이 즉시, 거부는 목록과 같은 위험 확인 문구를 거친다. 조회~처리 사이 상태가 바뀐 경우(422)는 안내 후 stale로 페이지가 다시 읽는다.
 */
const props = defineProps<{ productPublicId: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const productsApi = useAdminProducts()
const toast = useAdminToast()

const product = ref<AdminProductDetail | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(productPublicId: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  product.value = null
  try {
    const response = await productsApi.detail(productPublicId)
    if (sequence !== loadSequence) return
    product.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const busy = ref(false)
const rejectOpen = ref(false)

async function decide(target: Extract<AdminProductStatusTarget, 'SALE' | 'REJECTED'>): Promise<void> {
  const current = product.value
  if (!current || busy.value) return
  busy.value = true
  try {
    const response = await productsApi.changeStatus(current.productPublicId, current.status, target)
    toast.info(`${current.name} → ${ADMIN_PRODUCT_STATUS_LABEL[response.status]}`) // 상태 전환은 중립(상품 목록과 같은 토스트)
    rejectOpen.value = false
    emit('processed', 'done')
  } catch (error) {
    rejectOpen.value = false
    if (extractErrorCode(error) === 'PRODUCT_INVALID_STATE') {
      toast.warning(toAdminErrorMessage(error))
      emit('processed', 'stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    busy.value = false
  }
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.productPublicId, (productPublicId) => {
  rejectOpen.value = false
  void load(productPublicId)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-product-panel">
    <v-skeleton-loader v-if="loading" type="list-item-three-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-product-error">{{ loadError }}</v-alert>
    <template v-else-if="product">
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">셀러 · 카테고리</div>
          <div class="text-body-2">{{ product.sellerName ?? '-' }} · {{ product.categoryName ?? '-' }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">상태</div>
          <div class="text-body-2" data-testid="inbox-product-status">{{ ADMIN_PRODUCT_STATUS_LABEL[product.status] }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">판매가</div>
          <div class="text-body-2">{{ formatWon(product.basePrice) }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">옵션 · 이미지</div>
          <div class="text-body-2">{{ product.variants.length }}개 · {{ product.images.length }}장</div>
        </v-col>
      </v-row>
      <div v-if="product.status === 'PENDING'" class="d-flex flex-wrap ga-2 mb-2">
        <v-btn color="primary" variant="flat" :loading="busy" data-testid="inbox-product-approve" @click="decide('SALE')">승인</v-btn>
        <v-btn color="error" variant="outlined" :disabled="busy" data-testid="inbox-product-reject" @click="rejectOpen = true">거부</v-btn>
      </div>
    </template>

    <AdminConfirmDialog
      :open="rejectOpen"
      test-id="inbox-product-reject-dialog"
      title="상품 거부"
      risk
      :message="rejectConfirmMessage(product?.name ?? '')"
      confirm-label="거부"
      :loading="busy"
      @confirm="decide('REJECTED')"
      @cancel="rejectOpen = false"
    />
  </div>
</template>
