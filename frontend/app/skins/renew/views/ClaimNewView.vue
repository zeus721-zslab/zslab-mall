<script setup lang="ts">
import type { ClaimNewPageVm } from '~/skins/contracts/claim-new'
import type { OrderListTab } from '~/lib/constants/order-tabs'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewNotice from '../components/RenewNotice.vue'
import { CLAIM_TYPE_BADGE_TONE } from '../claim-type-tone'

// renew 클레임 신청(FE-73). 화면 상태는 classic과 같다: 잘못된 접근 / 입력 폼 / 접수 완료. 폼 동작·검증·문구·testid는 페이지 vm 그대로다.
// 사유는 네이티브 select, 교환 옵션은 실제 radio(모양만 꾸밈), 사진 첨부는 공용 ClaimAttachmentInput을 그대로 쓴다.
defineProps<{ vm: ClaimNewPageVm }>()

// 접수 완료 → 주문 내역의 클레임 탭(FE-80 · 클레임 상세 복귀 tabOfClaimType와 같은 값). 방금 낸 요청이 유형과 무관하게 보인다.
const CLAIM_LIST_TAB: OrderListTab = 'claim'

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const FIELD =
  'w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <MypageFrame :title="vm.isValidQuery ? `${vm.typeLabel} 요청` : '취소·반품·교환 요청'" active-to="/orders">
    <!-- 필수 query 누락·부정: 폼 진입 차단하고 주문 내역으로 유도(재시도 무의미라 링크 안내). -->
    <div v-if="!vm.isValidQuery" :class="[CARD, 'flex max-w-[720px] flex-col items-center py-14 text-center']">
      <p class="text-body text-sub">잘못된 접근입니다. 주문 상세에서 취소·반품·교환을 요청해 주세요.</p>
      <NuxtLink to="/orders" class="btn btn-primary btn-md mt-6">주문 내역으로</NuxtLink>
    </div>

    <!-- 제출 성공: 원주문 id 미보유라 주문 내역의 클레임 탭으로 유도. -->
    <div v-else-if="vm.submitted" :class="[CARD, 'flex max-w-[720px] flex-col items-center py-12 text-center']" data-testid="claim-submitted">
      <span class="flex h-20 w-20 items-center justify-center rounded-full bg-(--pastel-mint-bg) text-(--pastel-mint-ink)" aria-hidden="true">
        <svg class="h-10 w-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M5 12.5l4.5 4.5L19 7.5" />
        </svg>
      </span>
      <p class="mt-6 text-h2 text-ink">취소·반품·교환 요청이 접수되었습니다.</p>
      <p class="mt-2 text-body text-sub">쇼핑몰 승인 후 처리가 진행됩니다.</p>
      <!-- 환불 반영 시점 안내(FE-61): 환불로 이어지는 취소·반품 요청만·기간은 적지 않는다. -->
      <RenewNotice v-if="vm.claimType !== 'EXCHANGE'" tone="info" class="mt-6 w-full text-left" data-testid="claim-refund-timing">
        {{ vm.REFUND_TIMING_NOTICE }}
      </RenewNotice>
      <NuxtLink :to="{ path: '/orders', query: { tab: CLAIM_LIST_TAB } }" class="btn btn-primary btn-md mt-6" data-testid="claim-submitted-list-link">
        주문 내역으로
      </NuxtLink>
    </div>

    <div v-else class="max-w-[720px] space-y-6">
      <!-- 대상·안내 -->
      <section :class="CARD" aria-label="요청 대상">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-caption font-normal text-sub">요청 대상</p>
            <p class="mt-1 text-h3 text-ink">{{ vm.productName || '주문 품목' }}</p>
          </div>
          <!-- 유형 = 색 배지(FE-73 보완 1 · RenewBadge FE-80) -->
          <RenewBadge v-if="vm.claimType" :tone="CLAIM_TYPE_BADGE_TONE[vm.claimType]" data-testid="claim-type-badge">{{ vm.typeLabel }}</RenewBadge>
        </div>
        <RenewNotice tone="info" class="mt-5">
          <p data-testid="claim-type-guidance">{{ vm.typeGuidance }}</p>
          <p class="mt-1 font-normal">요청 후 쇼핑몰 승인이 필요합니다.</p>
        </RenewNotice>
      </section>

      <!-- 입력 폼: 사유·교환 옵션·첨부를 한 흰 카드에 두고 제출이 영역의 주 버튼이다. -->
      <form :class="[CARD, 'space-y-5']" @submit.prevent="vm.handleSubmit">
        <div>
          <label for="reasonCode" :class="LABEL">요청 사유</label>
          <select id="reasonCode" v-model="vm.reasonCode" required data-testid="claim-reason-code" :class="[FIELD, 'h-12']">
            <option value="" disabled>사유를 선택하세요</option>
            <option v-for="code in vm.reasonCodes" :key="code" :value="code" data-testid="claim-reason-option">
              {{ vm.CLAIM_REASON_LABELS[code] }}
            </option>
          </select>
        </div>

        <div>
          <label for="reasonDetail" :class="LABEL">상세 사유 (선택)</label>
          <textarea
            id="reasonDetail"
            v-model="vm.reasonDetail"
            :maxlength="vm.REASON_DETAIL_MAX"
            rows="4"
            :class="[FIELD, 'py-3']"
            placeholder="상세 사유를 입력하세요(선택)"
          ></textarea>
          <p class="mt-1 text-right text-caption font-normal tabular-nums text-sub">{{ vm.reasonDetail.length }}/{{ vm.REASON_DETAIL_MAX }}</p>
        </div>

        <!-- 교환 옵션 선택(FE-30-1): 같은 가격·판매 중 옵션만 후보. 0건이면 요청 불가 안내. -->
        <fieldset v-if="vm.isExchange" data-testid="exchange-options">
          <legend :class="LABEL">교환할 옵션</legend>
          <RenewNotice v-if="vm.optionsPending" tone="info" data-testid="exchange-options-loading">교환 가능한 옵션을 불러오는 중…</RenewNotice>
          <RenewNotice v-else-if="vm.optionsStatus === 'error'" tone="danger" data-testid="exchange-options-error">
            <p>교환 가능한 옵션을 불러오지 못했습니다.</p>
            <template #action>
              <button type="button" class="btn btn-sm bg-white text-primary max-md:min-h-11" @click="vm.refreshOptions()">다시 시도</button>
            </template>
          </RenewNotice>
          <RenewNotice v-else-if="vm.productDetail?.saleStopped" tone="info" data-testid="exchange-options-empty">
            판매가 중지된 상품은 교환할 수 없습니다. 반품을 이용해 주세요.
          </RenewNotice>
          <RenewNotice v-else-if="vm.exchangeOptions.length === 0" tone="info" data-testid="exchange-options-empty">
            같은 가격으로 교환 가능한 다른 옵션이 없습니다. 반품을 이용해 주세요.
          </RenewNotice>
          <ul v-else class="grid gap-2 sm:grid-cols-2">
            <li v-for="option in vm.exchangeOptions" :key="option.variantPublicId">
              <label
                class="flex min-h-12 cursor-pointer items-center gap-3 rounded-control border border-line bg-white px-4 text-body font-semibold text-ink transition duration-fast ease-soft hover:border-ink has-checked:border-primary has-checked:bg-surface-muted"
              >
                <input
                  v-model="vm.exchangeVariantId"
                  type="radio"
                  name="exchangeVariantId"
                  :value="option.variantPublicId"
                  :disabled="vm.submitting"
                  class="h-5 w-5 shrink-0 cursor-pointer appearance-none rounded-full border-2 border-line bg-white transition duration-fast ease-soft checked:border-[6px] checked:border-primary focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
                  data-testid="exchange-option"
                >
                <span>{{ option.label }}</span>
              </label>
            </li>
          </ul>
        </fieldset>

        <!-- 반품·교환 사진(FE-29·FE-30): 상품불량·오배송 사유에서만 노출·선택·최대 5장(공용 컴포넌트 그대로) -->
        <ClaimAttachmentInput v-if="vm.attachmentAllowed" v-model="vm.attachments" :disabled="vm.submitting" />

        <RenewNotice v-if="vm.errorMessage" tone="danger">{{ vm.errorMessage }}</RenewNotice>

        <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="vm.submitDisabled" data-testid="claim-submit">
          <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none" aria-hidden="true"></span>
          {{ vm.submitting ? '요청 중…' : `${vm.typeLabel} 요청하기` }}
        </button>
      </form>
    </div>
  </MypageFrame>
</template>
