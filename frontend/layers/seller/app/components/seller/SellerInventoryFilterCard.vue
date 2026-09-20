<script setup lang="ts">
import { mdiClose, mdiMagnify, mdiRefresh } from '@mdi/js'
import type { SellerInventoryListQuery } from '#layers/seller/app/types/seller-product'
import { SELLER_PRODUCT_KEYWORD_MAX } from '#layers/seller/app/lib/constants/seller-product'

// 재고 필터 카드(Track 90-C-3). 검색어(상품명·SKU)는 로컬 입력값을 두고 검색 버튼·Enter로만 확정한다. productPublicId는 상품 목록에서 진입할 때 URL로
// 붙는 상품 한정 필터라 입력란 대신 해제 가능한 chip으로만 보여준다. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다.
const props = defineProps<{
  query: SellerInventoryListQuery
}>()

const emit = defineEmits<{
  apply: [patch: Partial<SellerInventoryListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

function submitKeyword(): void {
  emit('apply', { keyword: keywordInput.value.trim() })
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-inventory-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="6">
          <v-text-field
            v-model="keywordInput"
            label="상품명 · SKU"
            placeholder="상품명 또는 판매자 SKU 일부"
            :prepend-inner-icon="mdiMagnify"
            :maxlength="SELLER_PRODUCT_KEYWORD_MAX"
            hide-details
            clearable
            data-testid="filter-keyword"
            @keyup.enter="submitKeyword"
            @click:clear="emit('apply', { keyword: '' })"
          />
        </v-col>
        <v-col v-if="query.productPublicId" cols="12" md="6">
          <v-chip
            closable
            :close-icon="mdiClose"
            variant="tonal"
            color="primary"
            data-testid="filter-product-chip"
            @click:close="emit('apply', { productPublicId: null })"
          >
            상품 한정: {{ query.productPublicId }}
          </v-chip>
        </v-col>
      </v-row>
      <div class="d-flex align-center ga-2 mt-3">
        <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="emit('reset')">초기화</v-btn>
      </div>
    </v-card-text>
  </v-card>
</template>
