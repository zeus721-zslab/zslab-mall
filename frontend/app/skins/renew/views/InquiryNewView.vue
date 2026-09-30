<script setup lang="ts">
import type { InquiryNewPageVm } from '~/skins/contracts/inquiry-new'
import { INQUIRY_CATEGORIES, INQUIRY_CONTENT_MAX, INQUIRY_LIST_PATH, inquiryCategoryLabel } from '~/lib/constants/inquiry'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 운영자 문의 작성(Track 106-4). 카테고리 칩(필수) → 첨부 주문(선택) → 본문(카운터) → 남기기. 취소·반품·교환을 고르면 주문 화면에서
// 바로 신청할 수 있다는 안내를 보이되 제출은 막지 않는다. 입력값·검증·제출은 페이지 vm이 가진다.
defineProps<{ vm: InquiryNewPageVm }>()

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const FIELD =
  'w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <MypageFrame title="운영자에게 문의" active-to="/mypage/inquiries">
    <form class="max-w-[720px] space-y-6" data-testid="inquiry-new-form" @submit.prevent="vm.submit()">
      <section :class="CARD" aria-labelledby="inquiry-category-label">
        <p id="inquiry-category-label" :class="LABEL">문의 종류</p>
        <div class="flex flex-wrap gap-2" role="group" aria-labelledby="inquiry-category-label">
          <button
            v-for="category in INQUIRY_CATEGORIES"
            :key="category"
            type="button"
            class="chip"
            :aria-pressed="vm.category === category"
            :data-category="category"
            data-testid="inquiry-category"
            @click="vm.category = category"
          >{{ inquiryCategoryLabel(category) }}</button>
        </div>
        <RenewNotice v-if="vm.showClaimGuide" tone="info" class="mt-4" data-testid="inquiry-claim-guide">
          <p>취소·반품·교환은 주문 상세에서 바로 신청할 수 있어요.</p>
          <NuxtLink :to="vm.claimGuidePath" class="mt-1 inline-block font-semibold underline underline-offset-4" data-testid="inquiry-claim-guide-link">
            {{ vm.orderId ? '주문 상세로 가기' : '주문 내역으로 가기' }}
          </NuxtLink>
        </RenewNotice>
      </section>

      <section :class="CARD" aria-label="문의 내용">
        <label for="inquiry-order" :class="LABEL">관련 주문 <span class="font-normal text-sub">(선택)</span></label>
        <select id="inquiry-order" v-model="vm.orderId" :disabled="vm.orderOptionsPending" data-testid="inquiry-order" :class="[FIELD, 'h-12']">
          <option value="">선택 안 함</option>
          <option v-for="option in vm.orderOptions" :key="option.orderId" :value="option.orderId">{{ option.label }}</option>
        </select>
        <p v-if="vm.orderOptionsFailed" class="mt-1.5 text-caption font-normal text-sub" data-testid="inquiry-order-failed">
          최근 주문을 불러오지 못했어요. 주문을 고르지 않고 남길 수 있어요.
        </p>

        <label for="inquiry-content" :class="[LABEL, 'mt-6']">문의 내용</label>
        <textarea
          id="inquiry-content"
          v-model="vm.content"
          :maxlength="INQUIRY_CONTENT_MAX"
          rows="6"
          placeholder="궁금한 점을 적어 주세요. 운영자가 확인 후 답변드려요."
          :class="[FIELD, 'py-3']"
          data-testid="inquiry-content"
        ></textarea>
        <p class="mt-1 text-right text-caption font-normal tabular-nums text-sub" data-testid="inquiry-content-count">
          {{ vm.content.length }}/{{ INQUIRY_CONTENT_MAX }}
        </p>
      </section>

      <RenewNotice v-if="vm.errorText" tone="warning" data-testid="inquiry-new-error">{{ vm.errorText }}</RenewNotice>

      <div class="flex items-center justify-end gap-3">
        <NuxtLink :to="INQUIRY_LIST_PATH" class="btn btn-tertiary btn-md">취소</NuxtLink>
        <button type="submit" class="btn btn-primary btn-md" :disabled="vm.submitting" :aria-busy="vm.submitting" data-testid="inquiry-submit">
          {{ vm.submitting ? '남기는 중…' : '문의 남기기' }}
        </button>
      </div>
    </form>
  </MypageFrame>
</template>
