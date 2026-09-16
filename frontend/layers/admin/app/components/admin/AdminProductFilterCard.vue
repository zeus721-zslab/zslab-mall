<script setup lang="ts">
import { mdiMagnify, mdiRefresh } from '@mdi/js'
import type { AdminProductListQuery, AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import {
  ADMIN_PRODUCT_SOLD_OUT_OPTIONS,
  ADMIN_PRODUCT_SORT_OPTIONS,
  ADMIN_PRODUCT_STATUS_OPTIONS,
} from '#layers/admin/app/lib/constants/product'

// 필터 카드(FE-25). 검색어는 로컬 입력값을 두고 검색 버튼·Enter로만 확정한다(타이핑마다 URL·API가 흔들리지 않게).
// 드롭다운(상태·품절·셀러·카테고리·정렬)은 선택 즉시 확정. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다.
const props = defineProps<{
  query: AdminProductListQuery
  sellers: AdminSellerSummary[]
  categories: CategorySummary[]
  optionsError: boolean
}>()

const emit = defineEmits<{
  apply: [patch: Partial<AdminProductListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

const sellerItems = computed(() =>
  props.sellers.map((seller) => ({ value: seller.sellerPublicId, title: seller.companyName })),
)
const categoryItems = computed(() =>
  props.categories.map((category) => ({ value: category.categoryId, title: category.displayName })),
)

function submitKeyword(): void {
  emit('apply', { keyword: keywordInput.value.trim() })
}
</script>

<template>
  <v-card class="mb-4" data-testid="admin-product-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="4">
          <v-text-field
            v-model="keywordInput"
            label="상품명 · 상품 ID"
            placeholder="상품명 일부 또는 prd_… 정확히"
            :prepend-inner-icon="mdiMagnify"
            hide-details
            clearable
            data-testid="filter-keyword"
            @keyup.enter="submitKeyword"
            @click:clear="emit('apply', { keyword: '' })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.status"
            :items="ADMIN_PRODUCT_STATUS_OPTIONS"
            label="상태"
            hide-details
            clearable
            data-testid="filter-status"
            @update:model-value="(value) => emit('apply', { status: value ?? null })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.soldOut"
            :items="ADMIN_PRODUCT_SOLD_OUT_OPTIONS"
            label="품절"
            hide-details
            clearable
            data-testid="filter-soldout"
            @update:model-value="(value) => emit('apply', { soldOut: value ?? null })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.sellerPublicId"
            :items="sellerItems"
            label="셀러"
            hide-details
            clearable
            data-testid="filter-seller"
            @update:model-value="(value) => emit('apply', { sellerPublicId: value ?? null })"
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            :model-value="query.categoryId"
            :items="categoryItems"
            label="카테고리"
            hide-details
            clearable
            data-testid="filter-category"
            @update:model-value="(value) => emit('apply', { categoryId: value ?? null })"
          />
        </v-col>
      </v-row>
      <div class="d-flex align-center justify-space-between flex-wrap ga-2 mt-3">
        <div class="d-flex align-center ga-2">
          <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
          <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="emit('reset')">초기화</v-btn>
          <span v-if="optionsError" class="text-caption text-error">셀러·카테고리 목록을 불러오지 못했습니다.</span>
        </div>
        <!-- 정렬 select는 버튼 줄 인라인이라 compact(의도된 예외) -->
        <v-select
          :model-value="query.sort"
          :items="ADMIN_PRODUCT_SORT_OPTIONS"
          label="정렬"
          hide-details
          density="compact"
          style="max-width: 180px"
          data-testid="filter-sort"
          @update:model-value="(value) => emit('apply', { sort: value })"
        />
      </div>
    </v-card-text>
  </v-card>
</template>
