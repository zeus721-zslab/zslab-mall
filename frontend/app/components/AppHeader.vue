<script setup lang="ts">
// FE-09 STEP 3: FE-03 정적 셸을 auth·cart store에 배선. 검색·카테고리는 시각 셸만 유지(동작은 후속 트랙).
const auth = useAuthStore()
const cart = useCartStore()

// 로그아웃은 UI 계층에서 auth·cart를 순차 조합한다(store 간 결합은 store 밖에서).
function handleLogout(): void {
  auth.logout()
  cart.clear()
}
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-gray-100 bg-white">
    <div class="mx-auto flex max-w-[1240px] items-center gap-4 px-4 py-4 md:gap-6 md:px-6">
      <NuxtLink to="/" class="shrink-0 text-xl font-bold tracking-tight text-primary">
        zslab-mall
      </NuxtLink>

      <!-- 검색: 형태만·비율 크게 강조 (동작은 후속 트랙) -->
      <div class="flex-1">
        <label class="relative block">
          <span class="sr-only">상품 검색</span>
          <span class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-4 text-ink">
            <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35m1.35-5.4a7.5 7.5 0 11-15 0 7.5 7.5 0 0115 0z" />
            </svg>
          </span>
          <input
            type="search"
            placeholder="찾으시는 상품을 검색해 보세요"
            aria-label="상품 검색"
            class="w-full rounded-full border border-gray-200 bg-surface-section py-3 pl-12 pr-4 text-sm text-gray-900 placeholder-gray-400 transition duration-200 focus:border-gray-900 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-gray-900"
          />
        </label>
      </div>

      <!-- 장바구니: 항상 표시, 뱃지는 count>0(items.length)일 때만. /cart로 이동(BUYER 미들웨어가 진입 보호). -->
      <NuxtLink
        to="/cart"
        aria-label="장바구니"
        class="relative shrink-0 rounded-full p-2 text-ink transition duration-200 hover:bg-gray-100 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-gray-900"
      >
        <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 3h1.386c.51 0 .955.343 1.087.835l.383 1.437M7.5 14.25a3 3 0 00-3 3h15.75m-12.75-3h11.218c1.121-2.3 2.1-4.684 2.924-7.138a60.114 60.114 0 00-16.536-1.84M7.5 14.25L5.106 5.272M6 20.25a.75.75 0 11-1.5 0 .75.75 0 011.5 0zm12.75 0a.75.75 0 11-1.5 0 .75.75 0 011.5 0z" />
        </svg>
        <span
          v-if="cart.count > 0"
          class="absolute -right-0.5 -top-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-xs font-semibold text-primary-foreground"
        >
          {{ cart.count }}
        </span>
      </NuxtLink>

      <!-- 인증 분기: isAuthenticated computed 기준으로만 렌더(로컬 상태 이중화 금지·SSR/클라 쿠키값 일치) -->
      <Button v-if="auth.isAuthenticated" variant="ghost" size="sm" class="shrink-0" @click="handleLogout">
        로그아웃
      </Button>
      <NuxtLink
        v-else
        to="/login"
        class="shrink-0 text-sm font-medium text-ink transition duration-200 hover:text-primary"
      >
        로그인
      </NuxtLink>
    </div>

    <!-- 카테고리: 레이아웃 자리 확보용 비상호작용 placeholder. FE-10 카탈로그 트랙에서 실배선. -->
    <nav aria-label="카테고리" class="border-t border-gray-100">
      <div class="mx-auto flex h-11 max-w-[1240px] items-center px-4 md:px-6">
        <span class="select-none text-sm text-sub">카테고리</span>
      </div>
    </nav>
  </header>
</template>
