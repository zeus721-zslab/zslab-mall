<script setup lang="ts">
import { mdiMagnify, mdiRefresh } from '@mdi/js'
import type { SellerProductListQuery } from '#layers/seller/app/types/seller-product'
import type { CategorySummary } from '~/types/category'
import {
  SELLER_PRODUCT_KEYWORD_MAX,
  SELLER_PRODUCT_SORT_OPTIONS,
  SELLER_PRODUCT_STATUS_OPTIONS,
} from '#layers/seller/app/lib/constants/seller-product'

// 상품 필터 카드(Track 90-C-3·관리자 AdminProductFilterCard 복제·셀러 필터 없음). 검색어는 로컬 입력값을 두고 검색 버튼·Enter로만 확정한다.
// 상태·카테고리·정렬 select는 변경 즉시 확정. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다. 카테고리는 공개 API(루트 카테고리)를 부모가 내려준다.
const props = defineProps<{
  query: SellerProductListQuery
  categories: CategorySummary[]
}>()

const emit = defineEmits<{
  apply: [patch: Partial<SellerProductListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

const categoryItems = computed(() => props.categories.map((category) => ({ value: category.categoryId, title: category.displayName })))

function submitKeyword(): void {
  emit('apply', { keyword: keywordInput.value.trim() })
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-product-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="4">
          <v-text-field
            v-model="keywordInput"
            label="상품명"
            placeholder="상품명 일부"
            :prepend-inner-icon="mdiMagnify"
            :maxlength="SELLER_PRODUCT_KEYWORD_MAX"
            hide-details
            clearable
            data-testid="filter-keyword"
            @keyup.enter="submitKeyword"
            @click:clear="emit('apply', { keyword: '' })"
          />
        </v-col>
        <v-col cols="6" sm="4" md="3">
          <v-select
            :model-value="query.status"
            :items="SELLER_PRODUCT_STATUS_OPTIONS"
            label="상태"
            hide-details
            clearable
            data-testid="filter-status"
            @update:model-value="(value) => emit('apply', { status: value ?? null })"
          />
        </v-col>
        <v-col cols="6" sm="4" md="3">
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
        <v-col cols="12" sm="4" md="2">
          <v-select
            :model-value="query.sort"
            :items="SELLER_PRODUCT_SORT_OPTIONS"
            label="정렬"
            hide-details
            data-testid="filter-sort"
            @update:model-value="(value) => emit('apply', { sort: value })"
          />
        </v-col>
      </v-row>
      <div class="d-flex align-center ga-2 mt-3">
        <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="emit('reset')">초기화</v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>
