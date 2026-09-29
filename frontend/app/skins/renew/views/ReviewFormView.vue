<script setup lang="ts">
import type { ReviewFormPageVm } from '~/skins/contracts/review-form'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewNotice from '../components/RenewNotice.vue'
import RenewPhotoInput from '../components/RenewPhotoInput.vue'
import RenewRatingInput from '../components/RenewRatingInput.vue'

// renew 리뷰 작성·수정(Track 106-1 PR2). 한 화면 점진형: 상품 요약 → 큰 별점 → (별점을 고르면) 키워드 칩 → 사진 → 본문 → 하단 고정 등록 버튼.
// 화면 상태: 잘못된 접근 / 불러오는 중·실패(수정) / 숨김 리뷰 잠김(수정) / 입력 폼 / 완료(체크 그리기 → 상품 페이지 링크).
// 나타나는 구역은 옅게 떠오르며 들어오고(motion-safe), 움직임 줄이기 설정이면 바로 보인다.
defineProps<{ vm: ReviewFormPageVm }>()

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const SECTION_TITLE = 'text-body font-bold text-ink'
const REVEAL = {
  enterActiveClass: 'motion-safe:transition motion-safe:duration-normal motion-safe:ease-soft',
  enterFromClass: 'opacity-0 motion-safe:translate-y-2',
}
</script>

<template>
  <MypageFrame :title="vm.mode === 'create' ? '리뷰 쓰기' : '리뷰 수정'" active-to="/orders">
    <!-- 필수 query 누락·부정 -->
    <div v-if="!vm.isValidQuery" :class="[CARD, 'flex max-w-[720px] flex-col items-center py-14 text-center']" data-testid="review-invalid">
      <p class="text-body text-sub">잘못된 접근입니다. 주문 내역에서 리뷰를 써 주세요.</p>
      <NuxtLink to="/orders" class="btn btn-primary btn-md mt-6">주문 내역으로</NuxtLink>
    </div>

    <!-- 완료 -->
    <div v-else-if="vm.submitted" :class="[CARD, 'flex max-w-[720px] flex-col items-center py-12 text-center']" data-testid="review-submitted">
      <span class="flex h-20 w-20 items-center justify-center rounded-full bg-(--pastel-mint-bg) text-(--pastel-mint-ink)" aria-hidden="true">
        <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path class="review-check" d="M5 12.5l4.5 4.5L19 7.5" pathLength="1" />
        </svg>
      </span>
      <p class="mt-6 text-h2 text-ink" role="status">{{ vm.mode === 'create' ? '리뷰가 등록되었습니다.' : '리뷰를 수정했습니다.' }}</p>
      <p class="mt-2 text-body text-sub">소중한 후기 고맙습니다.</p>
      <NuxtLink :to="vm.productPath" class="btn btn-primary btn-md mt-6" data-testid="review-submitted-product-link">상품 페이지에서 보기</NuxtLink>
    </div>

    <!-- 수정: 불러오는 중 · 실패 -->
    <div v-else-if="vm.loading" :class="[CARD, 'h-64 max-w-[720px]']" aria-hidden="true"></div>
    <div v-else-if="vm.loadError" :class="[CARD, 'flex max-w-[720px] flex-col items-center py-14 text-center']" data-testid="review-load-error">
      <p class="text-body text-sub">{{ vm.loadError }}</p>
      <NuxtLink to="/orders" class="btn btn-primary btn-md mt-6">주문 내역으로</NuxtLink>
    </div>

    <div v-else class="max-w-[720px] space-y-6">
      <!-- 상품 요약 -->
      <section :class="CARD" aria-label="리뷰 대상">
        <p class="text-caption font-normal text-sub">리뷰 대상</p>
        <p class="mt-1 text-h3 text-ink" data-testid="review-product-name">{{ vm.productName || '주문 상품' }}</p>
        <p v-if="vm.optionLabel" class="mt-0.5 text-small font-normal text-sub">{{ vm.optionLabel }}</p>
      </section>

      <!-- 숨김 리뷰: 수정 불가 안내(D-237 보고 결정 2) -->
      <RenewNotice v-if="vm.locked" tone="warning" data-testid="review-locked">
        <p class="flex flex-wrap items-center gap-2"><RenewBadge tone="danger">비공개 처리됨</RenewBadge>비공개 처리된 리뷰는 수정할 수 없습니다.</p>
        <p v-if="vm.hiddenReason" class="mt-1 font-normal">사유: {{ vm.hiddenReason }}</p>
      </RenewNotice>

      <form v-else :class="[CARD, 'space-y-8 pb-0 md:pb-0']" novalidate @submit.prevent="vm.handleSubmit">
        <!-- 큰 별점 -->
        <fieldset class="flex flex-col items-center gap-2 text-center">
          <legend class="sr-only">별점</legend>
          <p :class="SECTION_TITLE" aria-hidden="true">상품은 어떠셨나요?</p>
          <RenewRatingInput v-model="vm.rating" name="review-rating" label="별점" :disabled="vm.submitting" />
          <p class="text-small font-semibold text-primary" aria-live="polite" data-testid="review-rating-label">{{ vm.ratingLabel }}</p>
        </fieldset>

        <Transition v-bind="REVEAL">
          <div v-if="vm.rating !== null" class="space-y-8">
            <!-- 키워드 칩(묶음별 · 최대 N개) -->
            <fieldset>
              <legend :class="SECTION_TITLE">
                어떤 점이 좋았나요? <span class="text-small font-normal text-sub">(선택 · 최대 {{ vm.KEYWORD_MAX }}개)</span>
              </legend>
              <p v-if="vm.keywordsPending" class="mt-3 text-small text-sub">키워드를 불러오는 중…</p>
              <RenewNotice v-else-if="vm.keywordsFailed" tone="danger" class="mt-3">
                <p>키워드를 불러오지 못했습니다. 키워드 없이 등록할 수도 있어요.</p>
                <template #action>
                  <button type="button" class="btn btn-sm bg-white text-primary max-md:min-h-11" @click="vm.retryKeywords()">다시 시도</button>
                </template>
              </RenewNotice>
              <div v-else class="mt-3 space-y-4">
                <div v-for="section in vm.keywordSections" :key="section.group" data-testid="review-keyword-section">
                  <p class="text-caption font-semibold text-sub">{{ section.label }}</p>
                  <div class="mt-2 flex flex-wrap gap-2">
                    <button
                      v-for="option in section.options"
                      :key="option.code"
                      type="button"
                      class="chip disabled:cursor-default disabled:opacity-40"
                      :aria-pressed="vm.selectedKeywords.includes(option.code)"
                      :disabled="vm.submitting || (vm.keywordLimitReached && !vm.selectedKeywords.includes(option.code))"
                      :data-testid="`review-keyword-${option.code}`"
                      @click="vm.toggleKeyword(option.code)"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                </div>
              </div>
            </fieldset>

            <!-- 사진(1장씩 순차 업로드) -->
            <fieldset>
              <legend :class="SECTION_TITLE">사진 <span class="text-small font-normal text-sub">(선택)</span></legend>
              <div class="mt-3">
                <RenewPhotoInput
                  v-model="vm.photos"
                  :upload="vm.uploadPhoto"
                  :max="vm.PHOTO_MAX"
                  :disabled="vm.submitting"
                  @update:busy="(busy: boolean) => (vm.photosBusy = busy)"
                  @update:failed="(count: number) => (vm.photosFailed = count)"
                />
              </div>
            </fieldset>

            <!-- 본문 -->
            <div>
              <label for="review-content" :class="SECTION_TITLE">리뷰 내용</label>
              <textarea
                id="review-content"
                v-model="vm.content"
                :maxlength="vm.CONTENT_MAX"
                rows="6"
                required
                aria-describedby="review-content-count"
                class="mt-3 w-full rounded-control border border-line bg-white px-4 py-3 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary"
                placeholder="상품을 써 보니 어땠는지 알려 주세요."
                :disabled="vm.submitting"
                data-testid="review-content"
              ></textarea>
              <p id="review-content-count" class="mt-1 text-right text-caption font-normal tabular-nums text-sub">
                {{ vm.content.length }}/{{ vm.CONTENT_MAX }}
              </p>
            </div>
          </div>
        </Transition>

        <!-- 하단 고정 등록 버튼: 폼 카드 바닥에 붙어 스크롤해도 보인다. -->
        <div class="sticky bottom-0 -mx-5 space-y-3 rounded-b-card border-t border-line bg-white px-5 py-4 md:-mx-6 md:px-6">
          <RenewNotice v-if="vm.errorMessage" tone="danger" data-testid="review-error">{{ vm.errorMessage }}</RenewNotice>
          <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="!vm.canSubmit" data-testid="review-submit">
            <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none" aria-hidden="true"></span>
            {{ vm.submitting ? '저장 중…' : vm.mode === 'create' ? '리뷰 등록' : '수정 완료' }}
          </button>
        </div>
      </form>
    </div>
  </MypageFrame>
</template>

<style scoped>
/* 완료 체크 그리기(pathLength=1 기준). 움직임 줄이기 설정이면 다 그린 상태로 바로 보인다. */
.review-check {
  stroke-dasharray: 1;
  stroke-dashoffset: 0;
}
@media (prefers-reduced-motion: no-preference) {
  .review-check {
    animation: review-check-draw 480ms var(--ease-soft) 120ms both;
  }
}
@keyframes review-check-draw {
  from {
    stroke-dashoffset: 1;
  }
}
</style>
