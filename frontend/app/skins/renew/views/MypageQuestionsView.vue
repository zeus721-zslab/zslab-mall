<script setup lang="ts">
import type { MypageQuestionsPageVm } from '~/skins/contracts/mypage-questions'
import type { MyProductQuestion } from '~/types/product-question'
import { formatDateTime } from '~/lib/utils/datetime'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewNotice from '../components/RenewNotice.vue'
import RenewQuestionEditForm from '../components/RenewQuestionEditForm.vue'

// renew 마이페이지 "내 질문"(Track 106-2). 카드마다 상품명(상세 링크) · 질문 · 답변 또는 "답변 대기" · 숨김이면 사유 · 수정·삭제(서버 editable·deletable).
// 페이저는 주문 내역과 같은 hasNext 이전/다음이다. 편집 중인 질문·삭제 확인은 화면 상태라 여기서 들고, 요청과 결과 처리는 페이지 vm이 한다.
const props = defineProps<{ vm: MypageQuestionsPageVm }>()

const editingId = ref<string | null>(null)
const confirmingDeleteId = ref<string | null>(null)

function startEdit(item: MyProductQuestion): void {
  confirmingDeleteId.value = null
  editingId.value = item.questionId
}

async function save(questionId: string, content: string): Promise<void> {
  if (await props.vm.saveEdit(questionId, content)) editingId.value = null
}

async function confirmDelete(questionId: string): Promise<void> {
  confirmingDeleteId.value = null
  await props.vm.remove(questionId)
}

function dateOf(value: string): string {
  return formatDateTime(value).slice(0, 10)
}

const CARD = 'rounded-card bg-white shadow-e1'
const SKELETON = 'rounded-full bg-surface-muted'
</script>

<template>
  <MypageFrame title="내 질문">
    <div v-if="vm.notice" class="mb-6" data-testid="my-question-notice">
      <RenewNotice :tone="vm.notice.tone">{{ vm.notice.text }}</RenewNotice>
    </div>

    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div v-for="index in 3" :key="index" :class="[CARD, 'space-y-3 p-5 md:p-6']">
        <div :class="[SKELETON, 'h-3 w-1/4']"></div>
        <div :class="[SKELETON, 'h-4 w-2/3']"></div>
      </div>
    </div>

    <!-- 에러 -->
    <CommonErrorState v-else-if="vm.error" message="내 질문을 불러오지 못했습니다" @retry="vm.retry" />

    <!-- 빈 목록 -->
    <div v-else-if="vm.items.length === 0 && vm.page === 0" :class="[CARD, 'flex flex-col items-center px-6 py-16 text-center']" data-testid="my-questions-empty">
      <p class="break-keep text-h3 text-ink">아직 남긴 질문이 없어요</p>
      <p class="mt-2 text-body text-sub">상품 상세의 "이 상품, 물어보세요"에서 셀러에게 질문할 수 있어요.</p>
    </div>

    <template v-else>
      <ul class="space-y-4" data-testid="my-questions-list">
        <li v-for="item in vm.items" :key="item.questionId" :class="[CARD, 'p-5 md:p-6']" data-testid="my-question-item">
          <div class="flex flex-wrap items-center gap-2">
            <NuxtLink
              v-if="item.productPublicId"
              :to="`/products/${item.productPublicId}`"
              class="text-body font-semibold text-ink underline-offset-4 hover:underline"
              data-testid="my-question-product"
            >{{ item.productName }}</NuxtLink>
            <span v-else class="text-body font-semibold text-sub">판매가 종료된 상품</span>
            <span class="text-caption font-normal tabular-nums text-sub">{{ dateOf(item.createdAt) }}</span>
          </div>

          <RenewQuestionEditForm
            v-if="editingId === item.questionId"
            :initial-content="item.content"
            :pending="vm.actionPendingId === item.questionId"
            @save="(content: string) => save(item.questionId, content)"
            @cancel="editingId = null"
          />
          <p v-else class="mt-3 whitespace-pre-line break-keep text-body text-ink" data-testid="my-question-content">
            <span class="font-semibold">Q. </span>{{ item.content }}
          </p>

          <p v-if="item.status === 'HIDDEN'" class="mt-2 text-small text-(--pastel-pink-ink)" data-testid="my-question-hidden">
            관리자가 숨긴 질문이에요{{ item.hiddenReason ? ` · 사유: ${item.hiddenReason}` : '' }}
          </p>

          <div v-if="item.answerContent !== undefined" class="mt-3 rounded-card bg-surface-muted px-4 py-3" data-testid="my-question-answer">
            <p class="whitespace-pre-line break-keep text-body text-ink"><span class="font-semibold">A. </span>{{ item.answerContent }}</p>
            <p v-if="item.answeredAt" class="mt-1 text-caption font-normal tabular-nums text-sub">셀러 답변 · {{ dateOf(item.answeredAt) }}</p>
          </div>
          <div v-else class="mt-3">
            <RenewBadge tone="warning" data-testid="my-question-waiting">답변 대기</RenewBadge>
          </div>

          <div v-if="(item.editable || item.deletable) && editingId !== item.questionId" class="mt-3 flex flex-wrap items-center gap-2">
            <template v-if="confirmingDeleteId === item.questionId">
              <span class="text-small text-sub">이 질문을 삭제할까요?</span>
              <button type="button" class="btn btn-secondary btn-sm" :disabled="vm.actionPendingId !== null" data-testid="my-question-delete-confirm" @click="confirmDelete(item.questionId)">삭제</button>
              <button type="button" class="btn btn-tertiary btn-sm" @click="confirmingDeleteId = null">취소</button>
            </template>
            <template v-else>
              <button v-if="item.editable" type="button" class="btn btn-tertiary btn-sm -ml-4" data-testid="my-question-edit" @click="startEdit(item)">수정</button>
              <button v-if="item.deletable" type="button" class="btn btn-tertiary btn-sm" data-testid="my-question-delete" @click="confirmingDeleteId = item.questionId">삭제</button>
            </template>
          </div>
        </li>
      </ul>

      <!-- 페이저: hasNext 기준 이전/다음(주문 내역과 같다). -->
      <div class="mt-8 flex items-center justify-center gap-3">
        <button type="button" class="btn btn-secondary btn-md" :disabled="vm.page === 0" @click="vm.movePage(vm.page - 1)">이전</button>
        <span class="min-w-8 text-center text-body font-semibold tabular-nums text-ink">{{ vm.page + 1 }}</span>
        <button type="button" class="btn btn-secondary btn-md" :disabled="!vm.hasNext" @click="vm.movePage(vm.page + 1)">다음</button>
      </div>
    </template>
  </MypageFrame>
</template>
