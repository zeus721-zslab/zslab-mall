<script setup lang="ts">
import type { AdminProductDetail, AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import type { ProductForm } from '#layers/admin/app/types/admin-product-form'
import { detailToForm } from '#layers/admin/app/lib/admin-product-form'
import { resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '상품 수정 · zslab-mall 관리자' })

// 상품 수정(FE-26). 상세를 폼으로 변환해 띄운다. 미존재(404)는 안내 + 목록 이동. ?partial=1은 등록 후속 단계 실패에서 넘어온 경우(안내 표시).
const route = useRoute()
const productsApi = useAdminProducts()
const productPublicId = computed<string>(() => String(route.params.id ?? ''))
const backPath = computed(() => resolveBackPath(route.query.back))
const partial = computed(() => route.query.partial === '1')

const initial = ref<ProductForm | null>(null)
const detail = ref<AdminProductDetail | null>(null)
const sellers = ref<AdminSellerSummary[]>([])
const categories = ref<CategorySummary[]>([])
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)
// 폼 재마운트 키(재조회 후 initial 교체 시 폼 상태를 새로 만든다).
const formKey = ref(0)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    const [detailResponse, sellerList, categoryList] = await Promise.all([
      productsApi.detail(productPublicId.value), productsApi.sellers(), productsApi.categories(),
    ])
    detail.value = detailResponse
    sellers.value = sellerList
    categories.value = categoryList
    initial.value = detailToForm(detailResponse)
    formKey.value += 1
  } catch (error) {
    if (extractErrorCode(error) === 'PRODUCT_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div>
    <AdminPageHeader title="상품 수정" :description="detail?.name ?? productPublicId" />
    <v-alert v-if="partial" type="warning" class="mb-4" data-testid="partial-alert">
      상품은 등록되었지만 이미지·옵션 저장 중 실패한 단계가 있습니다. 내용을 확인한 뒤 다시 저장하세요.
    </v-alert>
    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>
    <v-card v-else-if="notFound" data-testid="product-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">상품을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">삭제되었거나 존재하지 않는 상품입니다: {{ productPublicId }}</p>
        <v-btn color="primary" :to="backPath" data-testid="back-to-list">목록으로</v-btn>
      </v-card-text>
    </v-card>
    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="product-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>
    <AdminProductForm
      v-else-if="initial"
      :key="formKey"
      mode="edit"
      :initial="initial"
      :sellers="sellers"
      :categories="categories"
      :seller-name="detail?.sellerName"
      :back-path="backPath"
    />
  </div>
</template>
