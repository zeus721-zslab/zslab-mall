<script setup lang="ts">
import { mdiMagnify, mdiRefresh } from '@mdi/js'
import type { SellerOrderListQuery } from '#layers/seller/app/types/seller-order'
import { ORDER_ITEM_STATUS_LABELS } from '~/lib/constants/claim'
import { SELLER_ORDER_ITEM_STATUS_CODES, SELLER_ORDER_KEYWORD_MAX } from '#layers/seller/app/lib/constants/seller-order'
import { isPeriodInverted, normalizeDateOnly } from '#layers/seller/app/lib/seller-order-query'

// 품목 필터 카드(Track 90-B-3·관리자 AdminOrderFilterCard 복제). 검색어는 로컬 입력값을 두고 검색 버튼·Enter로만 확정한다.
// 기간(결제일·네이티브 date 2개)·품목 상태 select는 변경 즉시 확정. 모든 확정은 emit('apply')로 부모(URL 단일 소스)에 넘긴다. 정렬은 결제일 최신순 고정(BE).
const props = defineProps<{
  query: SellerOrderListQuery
}>()

const emit = defineEmits<{
  apply: [patch: Partial<SellerOrderListQuery>]
  reset: []
}>()

const keywordInput = ref<string>(props.query.keyword)
watch(() => props.query.keyword, (next) => { keywordInput.value = next })

const statusItems = SELLER_ORDER_ITEM_STATUS_CODES.map((value) => ({ value, title: ORDER_ITEM_STATUS_LABELS[value] }))

const periodInverted = computed(() => isPeriodInverted(props.query))

function submitKeyword(): void {
  emit('apply', { keyword: keywordInput.value.trim() })
}

/** 네이티브 date 입력값(yyyy-MM-dd 또는 빈 문자열) → 검증 후 확정. 형식이 아니면 해제. */
function applyDate(key: 'from' | 'to', value: string | null): void {
  emit('apply', { [key]: normalizeDateOnly(value === '' ? null : value) })
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-order-filters">
    <v-card-text class="pa-4">
      <v-row dense align="center">
        <v-col cols="12" md="5">
          <v-text-field
            v-model="keywordInput"
            label="상품명 · 주문번호"
            placeholder="상품명 일부 또는 주문번호 정확히"
            :prepend-inner-icon="mdiMagnify"
            :maxlength="SELLER_ORDER_KEYWORD_MAX"
            hide-details
            clearable
            data-testid="filter-keyword"
            @keyup.enter="submitKeyword"
            @click:clear="emit('apply', { keyword: '' })"
          />
        </v-col>
        <v-col cols="12" sm="4" md="3">
          <v-select
            :model-value="query.status"
            :items="statusItems"
            label="품목 상태"
            hide-details
            clearable
            data-testid="filter-status"
            @update:model-value="(value) => emit('apply', { status: value ?? null })"
          />
        </v-col>
        <v-col cols="6" sm="4" md="2">
          <v-text-field
            :model-value="query.from ?? ''"
            type="date"
            label="결제일 시작"
            hide-details
            data-testid="filter-from"
            @update:model-value="(value) => applyDate('from', value)"
          />
        </v-col>
        <v-col cols="6" sm="4" md="2">
          <v-text-field
            :model-value="query.to ?? ''"
            type="date"
            label="결제일 종료"
            hide-details
            data-testid="filter-to"
            @update:model-value="(value) => applyDate('to', value)"
          />
        </v-col>
      </v-row>
      <div class="d-flex align-center ga-2 mt-3">
        <v-btn color="primary" :prepend-icon="mdiMagnify" data-testid="filter-search" @click="submitKeyword">검색</v-btn>
        <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="emit('reset')">초기화</v-btn>
        <span v-if="periodInverted" class="text-caption text-error" data-testid="filter-period-error">시작일이 종료일보다 늦습니다.</span>
      </div>
    </v-card-text>
  </v-card>
</template>
