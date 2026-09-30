<script setup lang="ts">
import type { LayoutShellVm } from '~/skins/contracts/layout'
import RenewCategoryRow from '../components/RenewCategoryRow.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 레이아웃 셸. 헤더 상태·동작은 레이아웃이 vm으로 넘긴다(useAppHeader·FE-69) — classic AppHeader와 같은 기능·testid.
defineProps<{ vm: LayoutShellVm }>()

// 모바일(md 미만)에서 검색창은 아이콘으로 접고, 누르면 헤더 아래 한 줄로 펼친다(화면 상태만·데이터 아님).
const mobileSearchOpen = ref(false)

// 본문 바로가기(FE-82): 해시 이동 대신 main에 직접 포커스한다(URL 불변 · 라우터 스크롤 규칙과 무관).
const mainElement = ref<HTMLElement | null>(null)
function skipToMain(): void {
  mainElement.value?.focus()
}

// 푸터 회사소개·약관·개인정보처리방침은 페이지가 없어 준비 중 안내만 띄운다(FE-82 · FE-81 비밀번호 찾기와 같은 방식).
const footerNoticeShown = ref<boolean>(false)
const FOOTER_PLACEHOLDER_LABELS = ['회사소개', '이용약관', '개인정보처리방침'] as const

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const ICON_BUTTON =
  'relative flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-ink transition duration-fast ease-soft hover:bg-surface-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary'
const MENU_LINK = 'btn btn-tertiary btn-md shrink-0'
const FOOTER_LINK = 'transition duration-fast ease-soft hover:text-ink max-md:inline-flex max-md:min-h-11 max-md:items-center'
</script>

<template>
  <div class="flex min-h-screen flex-col bg-surface-page text-ink">
    <!-- 첫 포커스 요소. 포커스를 받을 때만 보인다(FE-82). sr-only·not-sr-only는 padding을 0으로 덮어 btn 여백이 사라져 투명도로 숨긴다. -->
    <a
      href="#main-content"
      class="btn btn-primary btn-md pointer-events-none fixed left-4 top-[calc(env(safe-area-inset-top,0px)+1rem)] z-60 opacity-0 focus:pointer-events-auto focus:opacity-100"
      data-testid="skip-to-main"
      @click.prevent="skipToMain"
    >
      본문 바로가기
    </a>
    <!-- ≥1024 높이는 --header-height 토큰으로 고정한다(상세 구매 영역 sticky가 같은 값 기준·FE-70). top은 상단 안전 영역만큼 내린다(FE-78). -->
    <header class="sticky top-[env(safe-area-inset-top,0px)] z-50 border-b border-line bg-white lg:h-(--header-height)">
      <div :class="[CONTAINER, 'flex flex-wrap items-center gap-x-2 gap-y-3 py-3 md:gap-x-6']">
        <!-- 높이 44는 터치 영역(FE-82). 헤더 줄 높이는 아이콘 버튼 44와 같아 바뀌지 않는다. -->
        <NuxtLink to="/" class="mr-auto inline-flex min-h-11 shrink-0 items-center text-h2 text-ink md:mr-0">
          zslab<span class="text-primary">.</span>mall
        </NuxtLink>

        <!-- 최상위 카테고리 메뉴(데스크톱). 모바일은 아래 가로 스크롤 줄. -->
        <nav aria-label="카테고리" class="hidden min-w-0 flex-1 lg:block" data-testid="category-menu-content">
          <ul class="flex items-center gap-1 overflow-x-auto">
            <li><NuxtLink to="/products" :class="MENU_LINK">전체</NuxtLink></li>
            <li v-for="category in vm.categoryMenuItems" :key="category.categoryId">
              <NuxtLink :to="`/categories/${category.categoryId}`" :class="MENU_LINK">{{ category.displayName }}</NuxtLink>
            </li>
          </ul>
        </nav>

        <!-- 검색: submit → /search?keyword=. 모바일은 아이콘으로 펼친 뒤 헤더 아래 전체 폭. -->
        <form
          role="search"
          data-testid="search-form"
          :class="[mobileSearchOpen ? 'block' : 'hidden', 'order-last w-full md:order-none md:ml-auto md:block md:w-72']"
          @submit.prevent="vm.handleSearchSubmit"
        >
          <label class="relative block">
            <span class="sr-only">상품 검색</span>
            <span class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-4 text-sub">
              <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35m1.35-5.4a7.5 7.5 0 11-15 0 7.5 7.5 0 0115 0z" />
              </svg>
            </span>
            <input
              v-model="vm.searchKeyword"
              type="search"
              name="keyword"
              placeholder="찾으시는 상품을 검색해 보세요"
              aria-label="상품 검색"
              data-testid="search-input"
              class="min-h-11 w-full rounded-control border border-line bg-surface-page py-2.5 pl-12 pr-4 text-body text-ink placeholder-sub transition duration-fast ease-soft focus:border-primary focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-primary"
            />
          </label>
        </form>

        <button
          type="button"
          :class="[ICON_BUTTON, 'md:hidden']"
          :aria-expanded="mobileSearchOpen"
          aria-label="검색 열기"
          @click="mobileSearchOpen = !mobileSearchOpen"
        >
          <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-4.35-4.35m1.35-5.4a7.5 7.5 0 11-15 0 7.5 7.5 0 0115 0z" />
          </svg>
        </button>

        <!-- 계정: BUYER 로그인이면 계정 메뉴(classic과 같은 항목·로그아웃), 아니면 로그인 링크. -->
        <DropdownMenu v-if="vm.isBuyerSignedIn">
          <DropdownMenuTrigger as-child>
            <button type="button" :class="ICON_BUTTON" aria-label="마이페이지" data-testid="account-menu-trigger">
              <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.5 20.1a7.5 7.5 0 0115 0A17.9 17.9 0 0112 21.75c-2.68 0-5.22-.58-7.5-1.65z" />
              </svg>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" class="min-w-44" data-testid="account-menu-content">
            <DropdownMenuItem v-for="item in vm.accountMenuItems" :key="item.to" as-child>
              <NuxtLink :to="item.to" class="w-full cursor-pointer">{{ item.label }}</NuxtLink>
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem class="cursor-pointer" data-testid="account-menu-logout" @select="vm.handleLogout">
              로그아웃
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <NuxtLink v-else to="/login" :class="MENU_LINK">로그인</NuxtLink>

        <!-- 장바구니: 뱃지는 담긴 품목 수(>0)일 때만. 숫자는 고정폭 숫자·포인트색.
             data-cart-target·data-cart-badge: 상세 담기 비행의 목적지·튐 대상(FE-99 · skins/renew/fly-to-cart.ts). -->
        <NuxtLink to="/cart" aria-label="장바구니" :class="ICON_BUTTON" data-cart-target>
          <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
          </svg>
          <span
            v-if="vm.cartCount > 0"
            data-cart-badge
            class="absolute right-0.5 top-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-caption tabular-nums text-primary-foreground"
          >
            {{ vm.cartCount }}
          </span>
        </NuxtLink>
      </div>

      <!-- 최상위 카테고리 메뉴(모바일·태블릿). 자체 탭 줄이 있는 화면(목록·카테고리 = 목록 카테고리 탭 FE-82 · 마이페이지 틀 = 메뉴 칩 줄 Track 105-4g-3)의
           <768은 줄이 겹겹이 쌓여 이 줄을 숨긴다. 상품 상세는 아래 헤더 밖에 그린다. -->
      <RenewCategoryRow
        v-if="!vm.hasUnpinnedCategoryRow"
        :items="vm.categoryMenuItems"
        :class="['border-t', vm.hasPageTabRow ? 'max-md:hidden' : '']"
      />
    </header>
    <!-- 상품 상세(FE-99): 카테고리 줄을 고정 헤더 밖에 둬 스크롤과 함께 사라지게 한다 — 헤더 아래 섹션 바·하단 바에 화면을 더 준다.
         헤더 안일 때와 같은 모습이 되도록 아래 테두리·흰 바탕을 준다. -->
    <RenewCategoryRow v-if="vm.hasUnpinnedCategoryRow" :items="vm.categoryMenuItems" class="border-b bg-white" />

    <main id="main-content" ref="mainElement" tabindex="-1" class="flex-1 focus:outline-hidden">
      <slot />
    </main>

    <footer class="mt-20 border-t border-line bg-surface-muted">
      <div :class="[CONTAINER, 'py-12']">
        <div class="flex flex-col gap-8 md:flex-row md:items-start md:justify-between">
          <div>
            <p class="text-h3 text-ink">zslab<span class="text-primary">.</span>mall</p>
            <p class="mt-2 text-small text-sub">쇼핑의 기준</p>
          </div>
          <nav class="flex flex-wrap gap-x-8 gap-y-3 text-small text-sub max-md:gap-y-0" aria-label="푸터">
            <button
              v-for="label in FOOTER_PLACEHOLDER_LABELS"
              :key="label"
              type="button"
              :class="FOOTER_LINK"
              data-testid="footer-placeholder-link"
              @click="footerNoticeShown = true"
            >
              {{ label }}
            </button>
            <NuxtLink to="/help" :class="FOOTER_LINK" data-testid="footer-help-link">고객센터</NuxtLink>
          </nav>
        </div>
        <RenewNotice v-if="footerNoticeShown" tone="info" class="mt-6" data-testid="footer-placeholder-notice">준비 중입니다.</RenewNotice>
        <p class="mt-10 border-t border-line pt-6 text-caption font-normal text-sub">© 2026 zslab-mall. All rights reserved.</p>
      </div>
    </footer>
  </div>
</template>
