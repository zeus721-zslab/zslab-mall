<script setup lang="ts">
import { mdiAlertCircleOutline, mdiPackageVariantClosed } from '@mdi/js'
import type { SellerProductSummary, SellerProductListQuery } from '#layers/seller/app/types/seller-product'
import type { CategorySummary } from '~/types/category'
import {
  DEFAULT_SELLER_PRODUCT_QUERY,
  hasActiveProductFilters,
  parseSellerProductQuery,
  toSellerProductRouteQuery,
} from '#layers/seller/app/lib/seller-product-query'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '상품 · zslab-mall 셀러' })

// 셀러 상품 목록(Track 90-C-3·90-C-1 API·주문 화면 골격 복제). URL query가 필터·정렬·페이지의 단일 소스: 화면 조작 → router.replace → route.query watch → 조회.
// 등록·수정 화면은 90-C-4(수정 버튼은 표에서 비활성). 재고는 별도 화면(/seller/products/inventory).
const route = useRoute()
const router = useRouter()
const productsApi = useSellerProducts()

const query = computed<SellerProductListQuery>(() => parseSellerProductQuery(route.query))

// 카테고리 필터 옵션은 공개 루트 카테고리 API(useCategories·관리자 전용 API 아님). 실패해도 목록은 영향 없이 옵션만 비운다.
const { data: categoryData } = useCategories()
const categories = computed<CategorySummary[]>(() => categoryData.value ?? [])

// ---------- 목록 ----------
const items = ref<SellerProductSummary[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await productsApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// router.replace가 반영되기 전에 연속 확정되면 앞 변경이 유실되므로 마지막으로 보낸 상태를 기준으로 합친다.
let pendingQuery: SellerProductListQuery | null = null
watch(() => route.query, () => { pendingQuery = null; void load() }, { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerProductListQuery>, resetPage = true): void {
  const next: SellerProductListQuery = { ...(pendingQuery ?? query.value), ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  pendingQuery = next
  void router.replace({ query: toSellerProductRouteQuery(next) })
}

function resetQuery(): void {
  pendingQuery = null
  void router.replace({ query: toSellerProductRouteQuery({ ...DEFAULT_SELLER_PRODUCT_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveProductFilters(query.value))
</script>

<template>
  <div data-testid="seller-products">
    <SellerPageHeader title="상품" description="내 상품을 조회합니다. 재고 수량과 입출고는 재고 화면에서, 승인·판매중지는 관리자가 처리합니다." />

    <SellerProductFilterCard :query="query" :categories="categories" @apply="applyQuery" @reset="resetQuery" />

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-product-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-product-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerProductTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-product-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiPackageVariantClosed" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <template v-if="filtersActive">
              <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 상품이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis mb-3">검색어·상태·카테고리 필터를 바꾸거나 초기화해 보세요.</p>
              <v-btn size="small" variant="outlined" @click="resetQuery">필터 초기화</v-btn>
            </template>
            <template v-else>
              <p class="text-subtitle-2 font-weight-medium mb-1">등록된 상품이 없습니다</p>
              <p class="text-body-2 text-medium-emphasis">상품 등록 화면은 준비 중입니다.</p>
            </template>
          </div>
        </template>
      </SellerProductTable>
    </v-card>
  </div>
</template>
