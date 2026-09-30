<script setup lang="ts">
import type { SellerProductQuestionItem } from '#layers/seller/app/types/seller-product-question'
import { SELLER_PRODUCT_QUESTION_PAGE_SIZES } from '#layers/seller/app/lib/seller-product-question-query'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'

// 상품 질문 표(Track 106-2 · v-data-table-server · SellerClaimTable 복제). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 행 버튼으로 답변 다이얼로그를 연다(미답변 = 답변하기 · 답변완료 = 답변 수정). 작성자 정보는 없다.
defineProps<{
  items: SellerProductQuestionItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  answer: [item: SellerProductQuestionItem]
}>()

/** 경과 N일: 미답변 행만 질문 작성일 기준으로 표시한다(방치 건 탐지 · 클레임 목록과 같은 임계). */
function waitingElapsed(item: SellerProductQuestionItem): ElapsedChip | null {
  return item.answerContent === undefined ? elapsedChip(item.createdAt) : null
}

const headers = [
  { title: '질문일', key: 'createdAt', sortable: false, width: 170 },
  { title: '상품', key: 'product', sortable: false },
  { title: '질문', key: 'content', sortable: false },
  { title: '답변', key: 'answer', sortable: false },
  { title: '', key: 'actions', sortable: false, width: 120, align: 'end' as const },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_PRODUCT_QUESTION_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="questionId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-question-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.createdAt`]="{ item }">
      <div class="text-body-2" data-testid="row-created-at">{{ formatDateTime(item.createdAt) }}</div>
      <v-chip v-if="waitingElapsed(item)" :class="`slr-chip slr-chip--${waitingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ waitingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #[`item.product`]="{ item }">
      <div class="slr-product-name" data-testid="row-product-name">{{ item.productName ?? '삭제된 상품' }}</div>
    </template>

    <template #[`item.content`]="{ item }">
      <div class="text-body-2" style="white-space: pre-line" data-testid="row-question">{{ item.content }}</div>
    </template>

    <template #[`item.answer`]="{ item }">
      <template v-if="item.answerContent !== undefined">
        <div class="text-body-2" style="white-space: pre-line" data-testid="row-answer">{{ item.answerContent }}</div>
        <div v-if="item.answeredAt" class="text-caption text-medium-emphasis">{{ formatDateTime(item.answeredAt) }}</div>
      </template>
      <v-chip v-else class="slr-chip slr-chip--warning" size="small" variant="flat" data-testid="row-unanswered">미답변</v-chip>
    </template>

    <template #[`item.actions`]="{ item }">
      <v-btn
        size="small"
        :variant="item.answerContent === undefined ? 'flat' : 'outlined'"
        color="primary"
        data-testid="row-answer-open"
        @click="emit('answer', item)"
      >
        {{ item.answerContent === undefined ? '답변하기' : '답변 수정' }}
      </v-btn>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
