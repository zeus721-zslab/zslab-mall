<script setup lang="ts">
import type { ClaimNewPageVm } from '~/skins/contracts/claim-new'

defineProps<{ vm: ClaimNewPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[640px] px-4 md:px-6">
      <!-- 필수 query 누락·부정: 폼 진입 차단하고 주문 내역으로 유도(재시도 무의미이므로 CommonErrorState 대신 링크 안내). -->
      <div v-if="!vm.isValidQuery" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <p class="text-sub">잘못된 접근입니다. 주문 상세에서 클레임을 요청해 주세요.</p>
        <Button variant="outline" size="lg" as-child>
          <NuxtLink to="/orders">주문 내역으로</NuxtLink>
        </Button>
      </div>

      <!-- 제출 성공: 인라인 성공 상태(toast 인프라 부재). 원주문 id 미보유라 주문 내역으로 유도. -->
      <div v-else-if="vm.submitted" class="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <p class="text-base font-medium text-ink">클레임이 접수되었습니다.</p>
        <p class="text-sm text-sub">쇼핑몰 승인 후 처리가 진행됩니다.</p>
        <!-- 환불 반영 시점 안내(FE-61): 환불로 이어지는 취소·반품 요청만·기간은 적지 않는다. -->
        <p v-if="vm.claimType !== 'EXCHANGE'" class="text-xs text-sub" data-testid="claim-refund-timing">{{ vm.REFUND_TIMING_NOTICE }}</p>
        <Button variant="outline" size="lg" as-child>
          <NuxtLink to="/orders">주문 내역으로</NuxtLink>
        </Button>
      </div>

      <template v-else>
        <NuxtLink to="/orders" class="mb-4 inline-block text-sm text-sub hover:underline">← 주문 내역</NuxtLink>
        <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">{{ vm.typeLabel }} 요청</h1>

        <!-- 대상·안내 -->
        <section class="mb-6 rounded-card border border-line p-5">
          <p class="text-sm text-sub">요청 대상</p>
          <p class="mt-1 text-base font-medium text-ink">{{ vm.productName || '주문 품목' }}</p>
          <p class="mt-3 text-sm text-ink">{{ vm.typeGuidance }}</p>
          <p class="mt-1 text-sm text-sub">요청 후 쇼핑몰 승인이 필요합니다.</p>
        </section>

        <!-- 입력 폼 -->
        <form class="space-y-4" @submit.prevent="vm.handleSubmit">
          <div class="space-y-1.5">
            <label for="reasonCode" class="block text-sm font-medium text-ink">요청 사유</label>
            <select
              id="reasonCode"
              v-model="vm.reasonCode"
              required
              class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            >
              <option value="" disabled>사유를 선택하세요</option>
              <option v-for="code in vm.reasonCodes" :key="code" :value="code">
                {{ vm.CLAIM_REASON_LABELS[code] }}
              </option>
            </select>
          </div>

          <div class="space-y-1.5">
            <label for="reasonDetail" class="block text-sm font-medium text-ink">상세 사유 (선택)</label>
            <textarea
              id="reasonDetail"
              v-model="vm.reasonDetail"
              :maxlength="vm.REASON_DETAIL_MAX"
              rows="4"
              class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
              placeholder="상세 사유를 입력하세요(선택)"
            ></textarea>
            <p class="text-right text-xs text-sub">{{ vm.reasonDetail.length }}/{{ vm.REASON_DETAIL_MAX }}</p>
          </div>

          <!-- 교환 옵션 선택(FE-30-1): 같은 가격·판매 중 옵션만 후보. 0건이면 요청 불가 안내. -->
          <fieldset v-if="vm.isExchange" class="space-y-1.5" data-testid="exchange-options">
            <legend class="block text-sm font-medium text-ink">교환할 옵션</legend>
            <p v-if="vm.optionsPending" class="text-sm text-sub" data-testid="exchange-options-loading">교환 가능한 옵션을 불러오는 중…</p>
            <div v-else-if="vm.optionsStatus === 'error'" class="flex items-center justify-between gap-3 rounded-control border border-line px-4 py-3" data-testid="exchange-options-error">
              <p class="text-sm text-soldout">교환 가능한 옵션을 불러오지 못했습니다.</p>
              <Button type="button" variant="outline" size="sm" @click="vm.refreshOptions()">다시 시도</Button>
            </div>
            <p v-else-if="vm.productDetail?.saleStopped" class="rounded-control border border-line px-4 py-3 text-sm text-sub" data-testid="exchange-options-empty">
              판매가 중지된 상품은 교환할 수 없습니다. 반품을 이용해 주세요.
            </p>
            <p v-else-if="vm.exchangeOptions.length === 0" class="rounded-control border border-line px-4 py-3 text-sm text-sub" data-testid="exchange-options-empty">
              같은 가격으로 교환 가능한 다른 옵션이 없습니다. 반품을 이용해 주세요.
            </p>
            <ul v-else class="space-y-2">
              <li v-for="option in vm.exchangeOptions" :key="option.variantPublicId">
                <label class="flex cursor-pointer items-center gap-3 rounded-control border border-line px-4 py-2.5 text-sm text-ink has-checked:border-gray-900">
                  <input v-model="vm.exchangeVariantId" type="radio" name="exchangeVariantId" :value="option.variantPublicId" :disabled="vm.submitting" data-testid="exchange-option">
                  <span>{{ option.label }}</span>
                </label>
              </li>
            </ul>
          </fieldset>

          <!-- 반품·교환 사진(FE-29·FE-30): 상품불량·오배송 사유에서만 노출·선택·최대 5장 -->
          <ClaimAttachmentInput v-if="vm.attachmentAllowed" v-model="vm.attachments" :disabled="vm.submitting" />

          <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>

          <Button type="submit" size="lg" class="w-full" :disabled="vm.submitDisabled" data-testid="claim-submit">
            {{ vm.submitting ? '요청 중…' : `${vm.typeLabel} 요청하기` }}
          </Button>
        </form>
      </template>
    </div>
  </div>
</template>
