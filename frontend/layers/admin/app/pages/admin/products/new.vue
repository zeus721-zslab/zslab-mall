<script setup lang="ts">
import type { AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import { emptyForm } from '#layers/admin/app/lib/admin-product-form'
import { resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '상품 등록 · zslab-mall 관리자' })

// 상품 등록(FE-26). 셀러·카테고리 옵션을 먼저 받아 폼을 띄운다. 등록 후속 단계 실패 시 생성된 상품의 수정 화면으로 전환한다(중복 등록 방지).
const route = useRoute()
const productsApi = useAdminProducts()
const backPath = computed(() => resolveBackPath(route.query.back))

const sellers = ref<AdminSellerSummary[]>([])
const categories = ref<CategorySummary[]>([])
const optionsError = ref<string | null>(null)
const optionsLoading = ref(true)

async function loadOptions(): Promise<void> {
  optionsLoading.value = true
  optionsError.value = null
  try {
    const [sellerList, categoryList] = await Promise.all([productsApi.sellers(), productsApi.categories()])
    sellers.value = sellerList
    categories.value = categoryList
  } catch (error) {
    console.warn('[admin/products/new] 셀러·카테고리 옵션 조회 실패', error)
    optionsError.value = '셀러·카테고리 목록을 불러오지 못했습니다.'
  } finally {
    optionsLoading.value = false
  }
}
onMounted(loadOptions)

function onCreatedPartially(productPublicId: string): void {
  void navigateTo({ path: `/admin/products/${productPublicId}`, query: { back: backPath.value, partial: '1' } }, { replace: true })
}
</script>

<template>
  <div>
    <AdminPageHeader title="상품 등록" description="기본정보·이미지·옵션을 입력하고 등록하면 승인대기 상태로 생성됩니다(승인 후 판매)." />
    <v-card v-if="optionsLoading" class="mb-4"><v-card-text><v-skeleton-loader type="article" /></v-card-text></v-card>
    <v-alert v-else-if="optionsError" type="error" class="mb-4" data-testid="options-error">
      {{ optionsError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="loadOptions">다시 시도</v-btn>
    </v-alert>
    <AdminProductForm
      v-else
      mode="create"
      :initial="emptyForm()"
      :sellers="sellers"
      :categories="categories"
      :back-path="backPath"
      @created-partially="onCreatedPartially"
    />
  </div>
</template>
