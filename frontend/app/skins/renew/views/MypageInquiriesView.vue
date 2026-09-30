<script setup lang="ts">
import type { MypageInquiriesPageVm } from '~/skins/contracts/mypage-inquiries'
import type { MyInquiry } from '~/types/inquiry'
import type { InquiryCategory } from '~/lib/constants/inquiry'
import { inquiryCategoryLabel } from '~/lib/constants/inquiry'
import { formatDateTime } from '~/lib/utils/datetime'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewInquiryEditForm from '../components/RenewInquiryEditForm.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 마이페이지 "내 문의"(Track 106-4 · 내 질문 화면 선례). 접힌 카드 = 카테고리 · 답변 상태 · 새 답변 표시 · 작성일 · 본문 첫 줄.
// 펼치면 본문 전체 · 첨부 주문 · 답변 · 수정·삭제(서버 editable·deletable). 펼침·편집·삭제 확인은 화면 상태라 여기서 들고, 요청과 결과 처리는
// 페이지 vm이 한다 — 답변이 달린 미확인 문의를 펼치면 vm.checkAnswer를 부른다(다시 부를지는 vm이 판단).
const props = defineProps<{ vm: MypageInquiriesPageVm }>()

const expandedIds = ref<string[]>(props.vm.initialOpenId ? [props.vm.initialOpenId] : [])
const editingId = ref<string | null>(null)
const confirmingDeleteId = ref<string | null>(null)

function isExpanded(item: MyInquiry): boolean {
  return expandedIds.value.includes(item.inquiryId)
}

function toggle(item: MyInquiry): void {
  if (isExpanded(item)) {
    expandedIds.value = expandedIds.value.filter((inquiryId) => inquiryId !== item.inquiryId)
    return
  }
  expandedIds.value = [...expandedIds.value, item.inquiryId]
  props.vm.checkAnswer(item)
}

// 펼친 채 들어온 문의(작성 직후 이동)나 재조회로 다시 받은 펼친 문의에 미확인 답변이 있으면 확인한다.
watch(
  () => props.vm.items,
  (items) => items.filter(isExpanded).forEach((item) => props.vm.checkAnswer(item)),
  { immediate: true },
)

function startEdit(item: MyInquiry): void {
  confirmingDeleteId.value = null
  editingId.value = item.inquiryId
}

async function save(inquiryId: string, category: InquiryCategory, content: string): Promise<void> {
  if (await props.vm.saveEdit(inquiryId, category, content)) editingId.value = null
}

async function confirmDelete(inquiryId: string): Promise<void> {
  confirmingDeleteId.value = null
  await props.vm.remove(inquiryId)
}

function dateOf(value: string): string {
  return formatDateTime(value).slice(0, 10)
}

const CARD = 'rounded-card bg-white shadow-e1'
const SKELETON = 'rounded-full bg-surface-muted'
</script>

<template>
  <MypageFrame title="내 문의">
    <template #actions>
      <NuxtLink :to="vm.newInquiryPath" class="btn btn-primary btn-sm" data-testid="my-inquiry-new">새 문의</NuxtLink>
    </template>

    <div v-if="vm.notice" class="mb-6" data-testid="my-inquiry-notice">
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
    <CommonErrorState v-else-if="vm.error" message="내 문의를 불러오지 못했습니다" @retry="vm.retry" />

    <!-- 빈 목록 -->
    <div v-else-if="vm.items.length === 0 && vm.page === 0" :class="[CARD, 'flex flex-col items-center px-6 py-16 text-center']" data-testid="my-inquiries-empty">
      <p class="break-keep text-h3 text-ink">아직 남긴 문의가 없어요</p>
      <p class="mt-2 text-body text-sub">자주 묻는 질문에서 답을 찾지 못했다면 "새 문의"로 운영자에게 남겨 주세요.</p>
    </div>

    <template v-else>
      <ul class="space-y-4" data-testid="my-inquiries-list">
        <li v-for="item in vm.items" :key="item.inquiryId" :class="[CARD, 'p-5 md:p-6']" :data-inquiry-id="item.inquiryId" data-testid="my-inquiry-item">
          <button
            type="button"
            class="flex w-full flex-col items-start gap-2 text-left"
            :aria-expanded="isExpanded(item)"
            data-testid="my-inquiry-toggle"
            @click="toggle(item)"
          >
            <span class="flex flex-wrap items-center gap-2">
              <RenewBadge tone="neutral" data-testid="my-inquiry-category">{{ inquiryCategoryLabel(item.category) }}</RenewBadge>
              <RenewBadge v-if="item.answerContent !== undefined" tone="success" data-testid="my-inquiry-answered">답변 완료</RenewBadge>
              <RenewBadge v-else tone="warning" data-testid="my-inquiry-waiting">답변 대기</RenewBadge>
              <RenewBadge v-if="item.unread" tone="info" data-testid="my-inquiry-unread">새 답변</RenewBadge>
              <span class="text-caption font-normal tabular-nums text-sub">{{ dateOf(item.createdAt) }}</span>
            </span>
            <span v-if="!isExpanded(item)" class="line-clamp-1 w-full break-all text-body text-ink" data-testid="my-inquiry-preview">{{ item.content }}</span>
          </button>

          <div v-if="isExpanded(item)" data-testid="my-inquiry-detail">
            <RenewInquiryEditForm
              v-if="editingId === item.inquiryId"
              :initial-category="item.category"
              :initial-content="item.content"
              :pending="vm.actionPendingId === item.inquiryId"
              @save="(category: InquiryCategory, content: string) => save(item.inquiryId, category, content)"
              @cancel="editingId = null"
            />
            <p v-else class="mt-3 whitespace-pre-line break-keep text-body text-ink" data-testid="my-inquiry-content">{{ item.content }}</p>

            <p v-if="item.orderId" class="mt-2 text-small text-sub" data-testid="my-inquiry-order">
              관련 주문
              <NuxtLink :to="`/orders/${item.orderId}`" class="font-semibold text-ink underline-offset-4 hover:underline">{{ item.orderNo ?? '주문 보기' }}</NuxtLink>
            </p>

            <div v-if="item.answerContent !== undefined" class="mt-3 rounded-card bg-surface-muted px-4 py-3" data-testid="my-inquiry-answer">
              <p class="whitespace-pre-line break-keep text-body text-ink"><span class="font-semibold">A. </span>{{ item.answerContent }}</p>
              <p v-if="item.answeredAt" class="mt-1 text-caption font-normal tabular-nums text-sub">운영자 답변 · {{ dateOf(item.answeredAt) }}</p>
            </div>

            <div v-if="(item.editable || item.deletable) && editingId !== item.inquiryId" class="mt-3 flex flex-wrap items-center gap-2">
              <template v-if="confirmingDeleteId === item.inquiryId">
                <span class="text-small text-sub">이 문의를 삭제할까요?</span>
                <button type="button" class="btn btn-secondary btn-sm" :disabled="vm.actionPendingId !== null" data-testid="my-inquiry-delete-confirm" @click="confirmDelete(item.inquiryId)">삭제</button>
                <button type="button" class="btn btn-tertiary btn-sm" @click="confirmingDeleteId = null">취소</button>
              </template>
              <template v-else>
                <button v-if="item.editable" type="button" class="btn btn-tertiary btn-sm -ml-4" data-testid="my-inquiry-edit" @click="startEdit(item)">수정</button>
                <button v-if="item.deletable" type="button" class="btn btn-tertiary btn-sm" data-testid="my-inquiry-delete" @click="confirmingDeleteId = item.inquiryId">삭제</button>
              </template>
            </div>
          </div>
        </li>
      </ul>

      <!-- 페이저: hasNext 기준 이전/다음(내 질문과 같다). -->
      <div class="mt-8 flex items-center justify-center gap-3">
        <button type="button" class="btn btn-secondary btn-md" :disabled="vm.page === 0" @click="vm.movePage(vm.page - 1)">이전</button>
        <span class="min-w-8 text-center text-body font-semibold tabular-nums text-ink">{{ vm.page + 1 }}</span>
        <button type="button" class="btn btn-secondary btn-md" :disabled="!vm.hasNext" @click="vm.movePage(vm.page + 1)">다음</button>
      </div>
    </template>
  </MypageFrame>
</template>
