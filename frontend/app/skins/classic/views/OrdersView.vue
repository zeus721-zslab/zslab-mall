<script setup lang="ts">
import type { OrdersPageVm } from '~/skins/contracts/orders'

defineProps<{ vm: OrdersPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">주문 내역</h1>

      <!-- 탭(FE-63): 주문 + 클레임 3유형. CategoryTabs와 같은 nav/aria-current 관례. -->
      <nav aria-label="주문내역 구분" data-testid="order-tabs" class="mb-6 overflow-x-auto">
        <ul class="flex gap-6 border-b border-gray-100">
          <li v-for="item in vm.ORDER_LIST_TABS" :key="item">
            <button
              type="button"
              class="whitespace-nowrap border-b-2 px-1 pb-3 text-sm transition duration-normal"
              :class="item === vm.tab ? 'border-ink font-semibold text-ink' : 'border-transparent text-sub hover:text-ink'"
              :aria-current="item === vm.tab ? 'page' : undefined"
              @click="vm.moveTo(item, 0)"
            >
              {{ vm.ORDER_LIST_TAB_LABELS[item] }}
            </button>
          </li>
        </ul>
      </nav>

      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-3">
        <div v-for="n in 5" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 -->
      <CommonErrorState v-else-if="vm.error" :message="vm.errorMessage" @retry="vm.retry" />

      <!-- 빈 목록 -->
      <CommonEmptyState v-else-if="vm.isEmpty" :message="vm.emptyMessage" />

      <!-- 목록 -->
      <template v-else>
        <ul class="space-y-3">
          <template v-if="vm.isOrderTab">
            <li v-for="order in vm.orders!.items" :key="order.orderId">
              <OrderSummaryCard :order="order" />
            </li>
          </template>
          <template v-else>
            <li v-for="claim in vm.claims!.items" :key="claim.publicId">
              <ClaimSummaryCard :claim="claim" />
            </li>
          </template>
        </ul>

        <!-- 페이징: hasNext 기준 이전/다음(누적 아님·page 왕복). 탭이 달라도 같은 페이저를 쓴다. -->
        <div class="mt-6 flex items-center justify-center gap-3">
          <Button variant="outline" size="sm" :disabled="vm.page === 0" @click="vm.moveTo(vm.tab, Math.max(vm.page - 1, 0))">
            이전
          </Button>
          <span class="text-sm text-sub">{{ vm.page + 1 }}</span>
          <Button variant="outline" size="sm" :disabled="!vm.hasNext" @click="vm.moveTo(vm.tab, vm.page + 1)">
            다음
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
