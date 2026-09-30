<script setup lang="ts">
import { mdiAlertCircleOutline, mdiCommentQuestionOutline } from '@mdi/js'
import type {
  SellerProductQuestionItem,
  SellerProductQuestionListQuery,
} from '#layers/seller/app/types/seller-product-question'
import {
  parseSellerProductQuestionQuery,
  toSellerProductQuestionRouteQuery,
} from '#layers/seller/app/lib/seller-product-question-query'
import {
  PRODUCT_QUESTION_ANSWERED_FILTERS,
  PRODUCT_QUESTION_ANSWERED_LABELS,
  type ProductQuestionAnsweredFilter,
} from '~/lib/constants/product-question'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProductQuestions } from '#layers/seller/app/composables/useSellerProductQuestions'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '상품 질문 · zslab-mall 셀러' })

// 셀러 상품 질문(Track 106-2 · 클레임 목록 골격 복제). 행 = 내 상품의 공개 질문. URL query(answered·page·size)가 단일 소스:
// 화면 조작 → router.replace → route.query watch → 조회. 기본 필터는 미답변 · 정렬은 BE 고정(오래된 순). 행 버튼 → 답변 다이얼로그 → 성공이면 다시 읽는다.
const route = useRoute()
const router = useRouter()
const questionsApi = useSellerProductQuestions()

const query = computed<SellerProductQuestionListQuery>(() => parseSellerProductQuestionQuery(route.query))
const answeredItems = PRODUCT_QUESTION_ANSWERED_FILTERS.map((value) => ({ value, title: PRODUCT_QUESTION_ANSWERED_LABELS[value] }))

const items = ref<SellerProductQuestionItem[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await questionsApi.list(query.value)
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

watch(() => route.query, () => void load(), { immediate: true, deep: true })

function applyQuery(patch: Partial<SellerProductQuestionListQuery>, resetPage = true): void {
  const next: SellerProductQuestionListQuery = { ...query.value, ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  void router.replace({ query: toSellerProductQuestionRouteQuery(next) })
}

const answerTarget = ref<SellerProductQuestionItem | null>(null)
function closeDialog(refresh: boolean): void {
  answerTarget.value = null
  if (refresh) void load()
}
</script>

<template>
  <div data-testid="seller-questions">
    <SellerPageHeader title="상품 질문" description="내 상품에 남겨진 구매자 질문에 답변합니다. 답변은 상품 페이지에 공개되며 등록 후에도 수정할 수 있습니다." />

    <v-card class="mb-4" data-testid="seller-question-filters">
      <v-card-text class="pa-4">
        <v-btn-toggle
          :model-value="query.answered"
          mandatory
          density="comfortable"
          variant="outlined"
          color="primary"
          data-testid="filter-answered"
          @update:model-value="(value: ProductQuestionAnsweredFilter | undefined) => value && applyQuery({ answered: value })"
        >
          <v-btn v-for="option in answeredItems" :key="option.value" :value="option.value" :data-testid="`filter-answered-${option.value}`">
            {{ option.title }}
          </v-btn>
        </v-btn-toggle>
      </v-card-text>
    </v-card>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-question-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="seller-question-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <SellerProductQuestionTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @answer="(item) => (answerTarget = item)"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-question-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiCommentQuestionOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <p class="text-subtitle-2 font-weight-medium mb-1">
              {{ query.answered === 'UNANSWERED' ? '답변을 기다리는 질문이 없습니다' : '질문이 없습니다' }}
            </p>
            <p class="text-body-2 text-medium-emphasis">구매자가 상품 페이지에서 질문하면 여기에 표시됩니다.</p>
          </div>
        </template>
      </SellerProductQuestionTable>
    </v-card>

    <SellerProductQuestionAnswerDialog
      :open="answerTarget !== null"
      :item="answerTarget"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
  </div>
</template>
