<script setup lang="ts">
import type { MypageHomeVm, MypagePageVm } from '~/skins/contracts/mypage'
import type { OrderSummary } from '~/types/order'
import { formatPhone } from '~/lib/format/phone'
import { orderSummaryStageLink } from '~/lib/utils/order-summary-stages'
import MypageFrame from '../components/MypageFrame.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewNotice from '../components/RenewNotice.vue'
import { ORDER_NO_CHIP_CLASS } from '../order-no-chip'

// renew 마이페이지 홈(FE-72). 인사 → 주문 현황(최근 N개월 5단계) → 구매 확정 대기 → 진행 중 클레임·기본 배송지 → 최근 주문.
// 섹션마다 로딩(스켈레톤)·실패(CommonErrorState + 재시도)를 따로 보여 준다 — 한 섹션이 실패해도 나머지는 그대로 보인다.
// renew는 mypageHome을 선언하므로 home이 항상 채워진다(renew HomeView가 vm을 필수로 받는 것과 같은 전제).
// FE-79: 인사 = 라벤더 띠 면 · 나머지 섹션 = 흰 카드(shadow-e1) · 불러온 내용 등장 모션 없음.
defineProps<{ vm: MypagePageVm & { home: MypageHomeVm } }>()

// 목록 썸네일 = 첫 품목(previewTitle 대표 품목) 이미지. 품목 요약이 없거나 이미지가 없으면 자리 표시.
function thumbnailOf(order: OrderSummary): string | null {
  const firstItem = order.items === undefined ? undefined : order.items[0]
  return firstItem === undefined || firstItem.thumbnailUrl === undefined ? null : firstItem.thumbnailUrl
}

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const SECTION_TITLE = 'text-h3 text-ink'
const SKELETON = 'rounded-2xl bg-surface-muted'
</script>

<template>
  <MypageFrame title="마이페이지">
    <div class="space-y-6">
      <!-- 1. 인사 -->
      <section class="rounded-(--panel-radius) bg-(--pastel-lavender-bg) p-6 md:p-8" aria-label="인사" data-testid="mypage-greeting">
        <div v-if="vm.home.profile.pending" class="h-11 w-2/3 rounded-2xl bg-white/60" aria-hidden="true"></div>
        <CommonErrorState v-else-if="vm.home.profile.error || !vm.home.profile.data" message="회원 정보를 불러오지 못했습니다" @retry="vm.home.profile.refresh" />
        <div v-else class="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
          <p class="break-keep text-h2 text-(--pastel-lavender-ink)">{{ vm.home.profile.data.name }}님, 반가워요</p>
          <NuxtLink to="/mypage/profile" class="btn btn-md self-start bg-white text-primary sm:self-auto">회원 정보 수정</NuxtLink>
        </div>
      </section>

      <!-- 2. 주문 현황 -->
      <section :class="CARD" aria-labelledby="mypage-order-status-title" data-testid="mypage-order-status">
        <div class="flex items-baseline justify-between gap-4">
          <h2 id="mypage-order-status-title" :class="SECTION_TITLE">주문 현황</h2>
          <p v-if="vm.home.summary.data" class="text-caption text-sub">최근 <span class="tabular-nums">{{ vm.home.summary.data.periodMonths }}</span>개월</p>
        </div>
        <div v-if="vm.home.summary.pending" class="mt-6 grid grid-cols-5 gap-2" aria-hidden="true">
          <div v-for="index in 5" :key="index" :class="[SKELETON, 'h-16']"></div>
        </div>
        <CommonErrorState v-else-if="vm.home.summary.error || !vm.home.summary.data" message="주문 현황을 불러오지 못했습니다" @retry="vm.home.summary.refresh" />
        <!-- 단계 숫자 = 그 단계 품목이 있는 최근 주문 목록 링크(D-224·FE-80). 0건도 링크를 유지한다(빈 목록 안내로 이동). -->
        <ol v-else class="mt-6 grid grid-cols-5">
          <li v-for="(stage, index) in vm.home.summaryStages" :key="stage.key" class="relative" :data-dimmed="stage.dimmed">
            <span v-if="index > 0" class="absolute left-0 top-1/2 h-10 w-px -translate-y-1/2 bg-line" aria-hidden="true"></span>
            <NuxtLink
              :to="orderSummaryStageLink(stage.key)"
              :aria-label="`${stage.label} ${stage.count}건 주문 보기`"
              :class="[
                'group mx-1 flex min-h-11 flex-col items-center gap-1 rounded-2xl px-1 py-1 text-center transition duration-fast ease-soft hover:bg-surface-page focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary',
              ]"
              data-testid="mypage-order-stage-link"
            >
              <!-- 0건 단계는 투명도 대신 숫자를 sub 색으로 낮춘다(FE-82 — 투명도는 라벨 대비를 1.87까지 떨어뜨렸다). -->
              <span :class="['text-h1 tabular-nums', stage.dimmed ? 'text-sub' : 'text-primary']">{{ stage.count }}</span>
              <span class="whitespace-nowrap text-caption text-sub md:text-small">{{ stage.label }}</span>
            </NuxtLink>
          </li>
        </ol>
      </section>

      <!-- 3. 구매 확정 대기: 배송 완료 품목이 있을 때만 -->
      <RenewNotice v-if="vm.home.summary.data && vm.home.summary.data.stages.delivered > 0" tone="info" data-testid="mypage-confirm-waiting">
        <p>
          배송 완료된 상품 <span class="tabular-nums">{{ vm.home.summary.data.stages.delivered }}</span>건이 구매 확정을 기다리고 있어요
        </p>
        <template #action>
          <NuxtLink to="/orders" class="btn btn-sm bg-white text-primary max-md:min-h-11">확정하러 가기</NuxtLink>
        </template>
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
            class="group mt-5 flex items-end justify-between gap-4 rounded-2xl transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
          >
            <span class="text-ink">
              <span :class="['text-display tabular-nums', vm.home.summary.data.activeClaimCount > 0 ? 'text-primary' : 'text-ink']">{{ vm.home.summary.data.activeClaimCount }}</span><span class="ml-1 text-body font-semibold">건</span>
            </span>
            <span class="flex min-h-11 items-center gap-1 text-small text-sub transition duration-fast ease-soft group-hover:text-ink">
              내역 보기
              <svg class="h-4 w-4 transition-transform duration-fast ease-soft motion-safe:group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
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
          <dl v-else-if="vm.home.defaultAddress" class="mt-5 space-y-2 text-body">
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">받는 분</dt>
              <dd class="min-w-0 font-semibold text-ink">{{ vm.home.defaultAddress.recipientName }}</dd>
            </div>
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">연락처</dt>
              <dd class="min-w-0 tabular-nums text-ink">{{ formatPhone(vm.home.defaultAddress.recipientPhone) }}</dd>
            </div>
            <div class="flex gap-4">
              <dt class="w-14 shrink-0 text-sub">주소</dt>
              <dd class="min-w-0 break-keep text-ink">
                (<span class="tabular-nums">{{ vm.home.defaultAddress.zonecode }}</span>) {{ vm.home.defaultAddress.addressRoad }}
                <template v-if="vm.home.defaultAddress.addressDetail"> {{ vm.home.defaultAddress.addressDetail }}</template>
              </dd>
            </div>
          </dl>
          <div v-else class="mt-5 flex flex-col items-start gap-4">
            <p class="text-body text-sub">등록된 기본 배송지가 없어요</p>
            <NuxtLink to="/mypage/addresses" class="btn btn-primary btn-md">배송지 등록</NuxtLink>
          </div>
        </section>
      </div>

      <!-- 5. 최근 주문: <640은 상태·금액을 정보 아래 줄로 내려 주문번호가 잘리지 않을 폭을 확보한다(FE-79). -->
      <section :class="CARD" aria-labelledby="mypage-recent-title" data-testid="mypage-recent-orders">
        <div class="flex items-center justify-between gap-4">
          <h2 id="mypage-recent-title" :class="SECTION_TITLE">최근 주문</h2>
          <NuxtLink to="/orders" class="btn btn-tertiary btn-sm max-md:min-h-11">더 보기</NuxtLink>
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
        <div v-else-if="vm.home.recentOrders.data.items.length === 0" class="flex flex-col items-center px-6 py-12 text-center">
          <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
            <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
            </svg>
          </span>
          <p class="mt-5 text-h3 text-ink">아직 주문한 상품이 없어요</p>
          <NuxtLink to="/products" class="btn btn-primary btn-md mt-5">쇼핑하러 가기</NuxtLink>
        </div>
        <ul v-else class="mt-2 divide-y divide-line">
          <li v-for="order in vm.home.recentOrders.data.items" :key="order.orderId">
            <NuxtLink
              :to="`/orders/${order.orderId}`"
              class="group -mx-3 flex items-center gap-4 rounded-2xl px-3 py-4 transition duration-fast ease-soft hover:bg-surface-page focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
              data-testid="mypage-recent-order"
            >
              <div class="h-16 w-16 shrink-0 overflow-hidden rounded-[14px] bg-(--image-placeholder)">
                <img
                  v-if="thumbnailOf(order)"
                  :src="thumbnailOf(order) ?? undefined"
                  :alt="order.previewTitle"
                  class="h-full w-full object-cover transition duration-fast ease-soft motion-safe:group-hover:scale-[1.04]"
                />
              </div>
              <div class="min-w-0 flex-1 sm:flex sm:items-center sm:gap-5">
                <div class="min-w-0 flex-1">
                  <p class="text-caption font-normal tabular-nums text-sub">{{ vm.home.formatDateTime(order.orderedAt) }}</p>
                  <!-- 주문번호 = 사람이 읽는 orderNo(모노 칩) · 없는 옛 응답이면 생략(내부 id 노출 금지 · Track 105-4g-3) -->
                  <p v-if="order.orderNo" class="mt-0.5"><span :class="ORDER_NO_CHIP_CLASS" data-testid="mypage-recent-order-no">{{ order.orderNo }}</span></p>
                  <p class="mt-1 truncate text-body font-semibold text-ink">{{ order.previewTitle }}</p>
                </div>
                <div class="mt-2 flex items-center gap-3 sm:mt-0 sm:shrink-0 sm:gap-5">
                  <RenewBadge tone="info">{{ vm.home.orderStatusLabel(order.status.code) }}</RenewBadge>
                  <p class="whitespace-nowrap text-ink">
                    <span class="text-body font-semibold tabular-nums">{{ order.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-caption">원</span>
                  </p>
                </div>
              </div>
            </NuxtLink>
          </li>
        </ul>
      </section>
    </div>
  </MypageFrame>
</template>
