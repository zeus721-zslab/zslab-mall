<script setup lang="ts">
import { BUYER_ROLE } from '~/lib/constants/auth'

// FE-09 STEP 3: FE-03 정적 셸을 auth·cart store에 배선. FE-20: 검색 submit·카테고리 드롭다운 배선.
// 로직은 renew 레이아웃과 공유하도록 useAppHeader로 추출했다(FE-69·마크업 불변).
const { auth, cart, searchKeyword, handleSearchSubmit, categoryMenuItems, accountMenuItems, handleLogout } = useAppHeader()
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-gray-100 bg-white">
    <div class="mx-auto flex max-w-[1240px] items-center gap-4 px-4 py-4 md:gap-6 md:px-6">
      <NuxtLink to="/" class="shrink-0 text-xl font-bold tracking-tight text-primary">
        zslab-mall
      </NuxtLink>

      <!-- 검색: submit → /search?keyword=(FE-20). 빈 값은 이동 없음. -->
      <form class="flex-1" role="search" data-testid="search-form" @submit.prevent="handleSearchSubmit">
        <label class="relative block">
          <span class="sr-only">상품 검색</span>
          <span class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-4 text-ink">
            <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35m1.35-5.4a7.5 7.5 0 11-15 0 7.5 7.5 0 0115 0z" />
            </svg>
          </span>
          <input
            v-model="searchKeyword"
            type="search"
            name="keyword"
            placeholder="찾으시는 상품을 검색해 보세요"
            aria-label="상품 검색"
            data-testid="search-input"
            class="w-full rounded-full border border-gray-200 bg-surface-section py-3 pl-12 pr-4 text-sm text-gray-900 placeholder-gray-400 transition duration-200 focus:border-gray-900 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-gray-900"
          />
        </label>
      </form>

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
      <!-- FE-22 D-2: 단일 쿠키 세션이라 ADMIN 토큰도 isAuthenticated=true → BUYER 전용 메뉴는 role=BUYER일 때만 노출한다. -->
      <!-- FE-19 계정 드롭다운: 라벨은 "내 계정" 고정(이름 조회 없음). 링크 항목은 as-child로 NuxtLink에 위임, 선택 시 자동 닫힘. -->
      <DropdownMenu v-if="auth.isAuthenticated && auth.role === BUYER_ROLE">
        <DropdownMenuTrigger as-child>
          <Button variant="ghost" size="sm" class="shrink-0" data-testid="account-menu-trigger">
            내 계정
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="min-w-44" data-testid="account-menu-content">
          <DropdownMenuItem v-for="item in accountMenuItems" :key="item.to" as-child>
            <NuxtLink :to="item.to" class="w-full cursor-pointer">{{ item.label }}</NuxtLink>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem class="cursor-pointer" data-testid="account-menu-logout" @select="handleLogout">
            로그아웃
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <NuxtLink
        v-else
        to="/login"
        class="shrink-0 text-sm font-medium text-ink transition duration-200 hover:text-primary"
      >
        로그인
      </NuxtLink>
    </div>

    <!-- 카테고리 드롭다운(FE-20): FE-19 dropdown-menu 재사용. 항목 = 전체 상품 / 구분선 / 루트 카테고리(/categories/[id]). -->
    <nav aria-label="카테고리" class="border-t border-gray-100">
      <div class="mx-auto flex h-11 max-w-[1240px] items-center px-4 md:px-6">
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button variant="ghost" size="sm" class="-ml-3" data-testid="category-menu-trigger">
              카테고리
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" class="min-w-44" data-testid="category-menu-content">
            <DropdownMenuItem as-child>
              <NuxtLink to="/products" class="w-full cursor-pointer">전체 상품</NuxtLink>
            </DropdownMenuItem>
            <template v-if="categoryMenuItems.length > 0">
              <DropdownMenuSeparator />
              <DropdownMenuItem v-for="category in categoryMenuItems" :key="category.categoryId" as-child>
                <NuxtLink :to="`/categories/${category.categoryId}`" class="w-full cursor-pointer">
                  {{ category.displayName }}
                </NuxtLink>
              </DropdownMenuItem>
            </template>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </nav>
  </header>
</template>
