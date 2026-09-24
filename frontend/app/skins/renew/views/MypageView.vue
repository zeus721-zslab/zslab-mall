<script setup lang="ts">
import type { MypageHomeVm, MypagePageVm } from '~/skins/contracts/mypage'
import type { OrderSummary } from '~/types/order'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 마이페이지 홈(FE-72). 인사 → 주문 현황(최근 N개월 5단계) → 구매 확정 대기 → 진행 중 클레임·기본 배송지 → 최근 주문.
// 섹션마다 로딩(스켈레톤)·실패(CommonErrorState + 재시도)를 따로 보여 준다 — 한 섹션이 실패해도 나머지는 그대로 보인다.
// renew는 mypageHome을 선언하므로 home이 항상 채워진다(renew HomeView가 vm을 필수로 받는 것과 같은 전제).
defineProps<{ vm: MypagePageVm & { home: MypageHomeVm } }>()

// 목록 썸네일 = 첫 품목(previewTitle 대표 품목) 이미지. 품목 요약이 없거나 이미지가 없으면 자리 표시.
function thumbnailOf(order: OrderSummary): string | null {
  const firstItem = order.items === undefined ? undefined : order.items[0]
  return firstItem === undefined || firstItem.thumbnailUrl === undefined ? null : firstItem.thumbnailUrl
}

const CARD = 'rounded-[28px] bg-white p-6 md:p-8'
const SECTION_TITLE = 'text-lg font-bold text-ink'
const SKELETON = 'rounded-2xl bg-surface-card'
// 불러온 내용이 스켈레톤 자리에 나타날 때 짧게 떠오른다(움직임 줄이기면 없음).
const ENTER = 'motion-safe:transition motion-safe:duration-300 motion-safe:ease-out motion-safe:starting:translate-y-1.5 motion-safe:starting:opacity-0'
const PILL_LINK =
  'inline-flex min-h-11 shrink-0 items-center justify-center rounded-full px-5 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2'
const MORE_LINK =
  'flex min-h-11 shrink-0 items-center rounded-full px-4 text-sm font-bold text-sub transition duration-200 hover:bg-surface-card hover:text-ink'
</script>

<template>
  <MypageFrame title="마이페이지">
    <div class="space-y-6">
      <!-- 1. 인사 -->
      <section class="rounded-[28px] bg-(--pastel-lavender-bg) p-6 md:p-8" aria-label="인사" data-testid="mypage-greeting">
        <div v-if="vm.home.profile.pending" class="h-11 w-2/3 rounded-2xl bg-white/60" aria-hidden="true"></div>
        <CommonErrorState v-else-if="vm.home.profile.error || !vm.home.profile.data" message="회원 정보를 불러오지 못했습니다" @retry="vm.home.profile.refresh" />
        <div v-else :class="[ENTER, 'flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between']">
          <p class="break-keep text-2xl font-bold tracking-tight text-(--pastel-lavender-ink) md:text-3xl">
            {{ vm.home.profile.data.name }}님, 반가워요
          </p>
          <NuxtLink to="/mypage/profile" :class="[PILL_LINK, 'self-start bg-white text-ink hover:bg-ink hover:text-white sm:self-auto']">
            회원 정보 수정
          </NuxtLink>
        </div>
      </section>

      <!-- 2. 주문 현황(표시 전용) -->
      <section :class="CARD" aria-labelledby="mypage-order-status-title" data-testid="mypage-order-status">
        <div class="flex items-baseline justify-between gap-4">
          <h2 id="mypage-order-status-title" :class="SECTION_TITLE">주문 현황</h2>
          <p v-if="vm.home.summary.data" class="text-xs font-bold text-sub">최근 {{ vm.home.summary.data.periodMonths }}개월</p>
        </div>
        <div v-if="vm.home.summary.pending" class="mt-6 grid grid-cols-5 gap-2" aria-hidden="true">
          <div v-for="index in 5" :key="index" :class="[SKELETON, 'h-16']"></div>
        </div>
        <CommonErrorState v-else-if="vm.home.summary.error || !vm.home.summary.data" message="주문 현황을 불러오지 못했습니다" @retry="vm.home.summary.refresh" />
        <ol v-else :class="[ENTER, 'mt-6 grid grid-cols-5']">
          <li
            v-for="(stage, index) in vm.home.summaryStages"
            :key="stage.key"
            :class="['relative flex flex-col items-center gap-1 px-1 text-center transition-opacity duration-300', stage.dimmed ? 'opacity-40' : '']"
            :data-dimmed="stage.dimmed"
          >
            <span v-if="index > 0" class="absolute left-0 top-1/2 h-10 w-px -translate-y-1/2 bg-line" aria-hidden="true"></span>
            <span :class="['font-mono text-2xl font-semibold md:text-3xl', stage.dimmed ? 'text-ink' : 'text-primary']">{{ stage.count }}</span>
            <span class="whitespace-nowrap text-xs font-bold text-sub md:text-sm">{{ stage.label }}</span>
          </li>
        </ol>
      </section>

      <!-- 3. 구매 확정 대기: 배송 완료 품목이 있을 때만 -->
      <RenewNotice
        v-if="vm.home.summary.data && vm.home.summary.data.stages.delivered > 0"
        tone="info"
        :class="[ENTER, 'md:px-8']"
        data-testid="mypage-confirm-waiting"
      >
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <p class="md:text-base">
            배송 완료된 상품 <span class="font-mono">{{ vm.home.summary.data.stages.delivered }}</span>건이 구매 확정을 기다리고 있어요
          </p>
          <NuxtLink to="/orders" :class="[PILL_LINK, 'self-start bg-white text-ink hover:bg-ink hover:text-white sm:self-auto']">확정하러 가기</NuxtLink>
        </div>
      </RenewNotice>

      <!-- 4. 진행 중 클레임 · 기본 배송지 -->
      <div class="grid gap-6 md:grid-cols-2">
        <section :class="CARD" aria-labelledby="mypage-claim-title" data-testid="mypage-claim-card">
          <h2 id="mypage-claim-title" :class="SECTION_TITLE">진행 중인 취소·반품·교환</h2>
          <div v-if="vm.home.summary.pending" :class="[SKELETON, 'mt-5 h-14 w-1/2']" aria-hidden="true"></div>
          <CommonErrorState v-else-if="vm.home.summary.error || !vm.home.summary.data" message="주문 현황을 불러오지 못했습니다" @retry="vm.home.summary.refresh" />
          <NuxtLink
            v-else
            :to="{ path: '/orders', query: { tab: 'claim' } }"
            :class="[ENTER, 'group mt-5 flex items-end justify-between gap-4 rounded-2xl transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary']"
          >
            <span class="text-ink">
              <span :class="['font-mono text-4xl font-semibold', vm.home.summary.data.activeClaimCount > 0 ? 'text-primary' : 'text-ink']">{{ vm.home.summary.data.activeClaimCount }}</span><span class="ml-1 text-base font-bold">건</span>
            </span>
            <span class="flex items-center gap-1 text-sm font-bold text-sub transition duration-200 group-hover:text-ink">
              내역 보기
              <svg class="h-4 w-4 transition-transform duration-200 motion-safe:group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </span>
          </NuxtLink>
        </section>

        <section :class="CARD" aria-labelledby="mypage-address-title" data-testid="mypage-default-address">
          <h2 id="mypage-address-title" :class="SECTION_TITLE">기본 배송지</h2>
          <div v-if="vm.home.addresses.pending" class="mt-5 space-y-3" aria-hidden="true">
            <div :class="[SKELETON, 'h-4 w-1/3']"></div>
            <div :class="[SKELETON, 'h-4 w-1/2']"></div>
            <div :class="[SKELETON, 'h-4 w-5/6']"></div>
          </div>
          <CommonErrorState v-else-if="vm.home.addresses.error || !vm.home.addresses.data" message="배송지를 불러오지 못했습니다" @retry="vm.home.addresses.refresh" />
          <dl v-else-if="vm.home.defaultAddress" :class="[ENTER, 'mt-5 space-y-2 text-sm']">
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">받는 분</dt>
              <dd class="min-w-0 font-bold text-ink">{{ vm.home.defaultAddress.recipientName }}</dd>
            </div>
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">연락처</dt>
              <dd class="min-w-0 font-mono text-ink">{{ vm.home.defaultAddress.recipientPhone }}</dd>
            </div>
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">주소</dt>
              <dd class="min-w-0 break-keep text-ink">
                ({{ vm.home.defaultAddress.zonecode }}) {{ vm.home.defaultAddress.addressRoad }}
                <template v-if="vm.home.defaultAddress.addressDetail"> {{ vm.home.defaultAddress.addressDetail }}</template>
              </dd>
            </div>
          </dl>
          <div v-else :class="[ENTER, 'mt-5 flex flex-col items-start gap-4']">
            <p class="text-sm text-sub">등록된 기본 배송지가 없어요</p>
            <NuxtLink to="/mypage/addresses" :class="[PILL_LINK, 'bg-primary text-primary-foreground hover:bg-primary-hover']">배송지 등록</NuxtLink>
          </div>
        </section>
      </div>

      <!-- 5. 최근 주문 -->
      <section :class="CARD" aria-labelledby="mypage-recent-title" data-testid="mypage-recent-orders">
        <div class="flex items-center justify-between gap-4">
          <h2 id="mypage-recent-title" :class="SECTION_TITLE">최근 주문</h2>
          <NuxtLink to="/orders" :class="MORE_LINK">더 보기</NuxtLink>
        </div>
        <div v-if="vm.home.recentOrders.pending" class="mt-4 space-y-4" aria-hidden="true">
          <div v-for="index in 3" :key="index" class="flex items-center gap-4">
            <div class="h-16 w-16 shrink-0 rounded-[14px] bg-(--image-placeholder)"></div>
            <div class="flex-1 space-y-2">
              <div :class="[SKELETON, 'h-3 w-1/3']"></div>
              <div :class="[SKELETON, 'h-4 w-2/3']"></div>
            </div>
          </div>
        </div>
        <CommonErrorState v-else-if="vm.home.recentOrders.error || !vm.home.recentOrders.data" message="최근 주문을 불러오지 못했습니다" @retry="vm.home.recentOrders.refresh" />
        <div v-else-if="vm.home.recentOrders.data.items.length === 0" :class="[ENTER, 'flex flex-col items-center px-6 py-12 text-center']">
          <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-card text-primary" aria-hidden="true">
            <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
            </svg>
          </span>
          <p class="mt-5 text-base font-bold text-ink">아직 주문한 상품이 없어요</p>
          <NuxtLink to="/products" :class="[PILL_LINK, 'mt-5 bg-primary text-primary-foreground hover:bg-primary-hover']">쇼핑하러 가기</NuxtLink>
        </div>
        <ul v-else :class="[ENTER, 'mt-2 divide-y divide-line']">
          <li v-for="order in vm.home.recentOrders.data.items" :key="order.orderId">
            <NuxtLink
              :to="`/orders/${order.orderId}`"
              class="group -mx-3 flex items-center gap-4 rounded-2xl px-3 py-4 transition duration-200 hover:bg-surface-page focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
              data-testid="mypage-recent-order"
            >
              <div class="h-16 w-16 shrink-0 overflow-hidden rounded-[14px] bg-(--image-placeholder)">
                <img
                  v-if="thumbnailOf(order)"
                  :src="thumbnailOf(order) ?? undefined"
                  :alt="order.previewTitle"
                  class="h-full w-full object-cover transition duration-500 ease-out motion-safe:group-hover:scale-[1.04]"
                />
              </div>
              <div class="min-w-0 flex-1">
                <p class="truncate text-xs text-sub">
                  {{ vm.home.formatDateTime(order.orderedAt) }} · <span class="font-mono">{{ order.orderId }}</span>
                </p>
                <p class="mt-1 truncate text-sm font-bold text-ink md:text-base">{{ order.previewTitle }}</p>
              </div>
              <div class="flex shrink-0 flex-col items-end gap-1.5 sm:flex-row sm:items-center sm:gap-5">
                <span class="rounded-full bg-surface-card px-3 py-1 text-xs font-bold text-ink">{{ vm.home.orderStatusLabel(order.status.code) }}</span>
                <p class="whitespace-nowrap text-ink">
                  <span class="font-mono text-sm font-semibold md:text-base">{{ order.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-xs">원</span>
                </p>
              </div>
            </NuxtLink>
          </li>
        </ul>
      </section>
    </div>
  </MypageFrame>
</template>
