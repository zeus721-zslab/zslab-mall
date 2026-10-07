<script setup lang="ts">
import { MessageCircleQuestionMark } from '@lucide/vue'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductQuestionItem } from '~/types/product-question'
import { PRODUCT_QUESTION_CONTENT_MAX } from '~/lib/constants/product-question'
import { PRODUCT_QUESTION_INPUT_ID, PRODUCT_SECTION_IDS } from '~/lib/constants/product-sections'
import { formatDateTime } from '~/lib/utils/datetime'
import ProductQuestionSuggestions from './ProductQuestionSuggestions.vue'
import RenewBadge from './RenewBadge.vue'
import RenewMoreButton from './RenewMoreButton.vue'
import RenewNotice from './RenewNotice.vue'
import RenewQuestionEditForm from './RenewQuestionEditForm.vue'

// 상품 상세 "이 상품, 물어보세요"(Track 106-2 · ProductDetailView 전용 · 리뷰 섹션 뒤). 대화형: 질문 입력 → 입력창 아래 즉시 답 카드(Q&A·리뷰·상품 설명)
// → 해결되지 않으면 "셀러에게 질문 남기기"로 공개 질문 등록 → 아래 공개 질문 목록(최신순 · 더보기). 작성자는 표시하지 않는다.
// 수정·삭제는 내가 쓴 미답변 질문에만 보인다. 편집 중인 질문·삭제 확인은 화면 상태라 여기서 들고, 요청과 결과 처리는 vm이 한다.
const props = defineProps<{ questions: ProductQuestionsVm }>()

const editingId = ref<string | null>(null)
const confirmingDeleteId = ref<string | null>(null)

const hasDraft = computed(() => props.questions.draft.trim() !== '')

function onInput(event: Event): void {
  props.questions.setDraft((event.target as HTMLTextAreaElement).value)
}

function canChange(item: ProductQuestionItem): boolean {
  return item.writtenByMe === true && item.answerContent === undefined
}

function startEdit(item: ProductQuestionItem): void {
  confirmingDeleteId.value = null
  editingId.value = item.questionId
}

async function save(questionId: string, content: string): Promise<void> {
  if (await props.questions.saveEdit(questionId, content)) editingId.value = null
}

async function confirmDelete(questionId: string): Promise<void> {
  confirmingDeleteId.value = null
  await props.questions.remove(questionId)
}

function dateOf(value: string): string {
  return formatDateTime(value).slice(0, 10)
}

const CARD = 'rounded-card bg-white px-5 py-8 shadow-e1 md:px-10 md:py-10'
</script>

<template>
  <section
    :id="PRODUCT_SECTION_IDS.questions"
    :class="['mt-20 scroll-mt-(--product-section-offset)', CARD]"
    aria-labelledby="product-questions-title"
    data-testid="product-questions"
  >
    <!-- 머리 -->
    <div>
      <p class="text-caption uppercase tracking-[0.12em] text-sub">Q&amp;A</p>
      <h2 id="product-questions-title" class="mt-1 text-h2 text-ink">이 상품, 물어보세요</h2>
      <p class="mt-2 text-body text-sub">궁금한 점을 적으면 기존 질문·리뷰·상품 설명에서 먼저 답을 찾아 드려요.</p>
    </div>

    <!-- 입력 -->
    <div class="mt-6">
      <label :for="PRODUCT_QUESTION_INPUT_ID" class="sr-only">상품에 대해 궁금한 점</label>
      <textarea
        :id="PRODUCT_QUESTION_INPUT_ID"
        :value="questions.draft"
        :maxlength="PRODUCT_QUESTION_CONTENT_MAX"
        rows="2"
        class="w-full rounded-control border border-line bg-white px-4 py-3 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary"
        placeholder="예: 세탁기에 돌려도 되나요?"
        data-testid="product-question-input"
        @input="onInput"
      ></textarea>
    </div>

    <!-- 즉시 답 카드 -->
    <ProductQuestionSuggestions v-if="questions.suggestions.length > 0" class="mt-4" :suggestions="questions.suggestions" />

    <!-- 등록 -->
    <div v-if="hasDraft" class="mt-4 flex flex-col items-start gap-2">
      <button
        type="button"
        class="btn btn-primary btn-md"
        :disabled="questions.submitting"
        :aria-busy="questions.submitting"
        data-testid="product-question-submit"
        @click="questions.submit()"
      >
        {{ questions.submitting ? '남기는 중…' : '해결되지 않았어요 → 셀러에게 질문 남기기' }}
      </button>
      <p class="text-caption font-normal text-sub">질문은 공개로 등록돼요. 답변은 마이페이지 "내 질문"에서 확인할 수 있어요.</p>
    </div>

    <div v-if="questions.notice" class="mt-4" data-testid="product-question-notice">
      <RenewNotice :tone="questions.notice.tone">{{ questions.notice.text }}</RenewNotice>
    </div>

    <!-- 공개 질문 목록 -->
    <div class="mt-10 border-t border-line pt-6">
      <h3 class="text-h3 text-ink">
        <!-- 건수: 첫 페이지 응답 전·조회 실패 시 기본값 0을 보이지 않는다(아래 목록의 pending 분기와 같은 판정 · FE-111 · UX-02). -->
        질문 <span v-if="!questions.pending && !questions.failed" class="tabular-nums" data-testid="product-questions-count">{{ questions.totalCount.toLocaleString('ko-KR') }}</span>
      </h3>

      <div v-if="questions.failed && questions.items.length === 0" class="mt-4">
        <CommonErrorState message="질문을 불러오지 못했습니다" @retry="questions.retry" />
      </div>

      <div v-else-if="questions.pending" class="space-y-4 py-6" aria-hidden="true">
        <div v-for="index in 2" :key="index" class="space-y-2">
          <div class="h-3 w-24 rounded-full bg-surface-muted"></div>
          <div class="h-4 w-3/4 rounded-full bg-surface-muted"></div>
        </div>
      </div>

      <div v-else-if="questions.items.length === 0" class="flex flex-col items-center gap-4 py-12 text-center" data-testid="product-questions-empty">
        <MessageCircleQuestionMark class="h-14 w-14 text-line" :stroke-width="1.5" aria-hidden="true" />
        <p class="text-sub">첫 질문을 남겨 보세요</p>
      </div>

      <ul v-else class="divide-y divide-line" data-testid="product-questions-list">
        <li v-for="item in questions.items" :key="item.questionId" class="py-6" data-testid="product-question-item">
          <div class="flex flex-wrap items-center gap-2">
            <RenewBadge v-if="item.answerContent === undefined" tone="warning" data-testid="question-waiting">답변 대기</RenewBadge>
            <RenewBadge v-else tone="success">답변 완료</RenewBadge>
            <span class="text-caption font-normal tabular-nums text-sub">{{ dateOf(item.createdAt) }}</span>
          </div>

          <RenewQuestionEditForm
            v-if="editingId === item.questionId"
            :initial-content="item.content"
            :pending="questions.actionPendingId === item.questionId"
            @save="(content: string) => save(item.questionId, content)"
            @cancel="editingId = null"
          />
          <p v-else class="mt-2 whitespace-pre-line break-keep text-body text-ink" data-testid="question-content">
            <span class="font-semibold">Q. </span>{{ item.content }}
          </p>

          <div v-if="item.answerContent !== undefined" class="mt-3 rounded-card bg-surface-muted px-4 py-3" data-testid="question-answer">
            <p class="whitespace-pre-line break-keep text-body text-ink"><span class="font-semibold">A. </span>{{ item.answerContent }}</p>
            <p v-if="item.answeredAt" class="mt-1 text-caption font-normal tabular-nums text-sub">셀러 답변 · {{ dateOf(item.answeredAt) }}</p>
          </div>

          <div v-if="canChange(item) && editingId !== item.questionId" class="mt-2 flex flex-wrap items-center gap-2" data-testid="question-actions">
            <template v-if="confirmingDeleteId === item.questionId">
              <span class="text-small text-sub">이 질문을 삭제할까요?</span>
              <button type="button" class="btn btn-secondary btn-sm" data-testid="question-delete-confirm" :disabled="questions.actionPendingId !== null" @click="confirmDelete(item.questionId)">삭제</button>
              <button type="button" class="btn btn-tertiary btn-sm" @click="confirmingDeleteId = null">취소</button>
            </template>
            <template v-else>
              <button type="button" class="btn btn-tertiary btn-sm -ml-4" data-testid="question-edit" @click="startEdit(item)">수정</button>
              <button type="button" class="btn btn-tertiary btn-sm" data-testid="question-delete" @click="confirmingDeleteId = item.questionId">삭제</button>
            </template>
          </div>
        </li>
      </ul>

      <div v-if="questions.hasNext" class="mt-4 flex justify-center">
        <RenewMoreButton :loading="questions.loadingMore" label="질문 더보기" @more="questions.loadMore()" />
      </div>
    </div>
  </section>
</template>
