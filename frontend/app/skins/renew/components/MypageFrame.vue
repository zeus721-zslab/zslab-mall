<script setup lang="ts">
import { MYPAGE_MENU_ITEMS } from '~/lib/constants/mypage-menu'
import { followActiveItem } from '../scroll-active'
import { trackScrollEdges } from '../scroll-edges'

// renew 마이페이지 공통 틀(FE-72): 사이드 메뉴 + 제목 + 본문(slot). 메뉴는 정의(lib/constants/mypage-menu)를 그대로 렌더한다.
// 현재 위치는 NuxtLink가 경로가 정확히 일치할 때만 붙이는 aria-current로 표시한다 — 홈(/mypage)은 하위 화면에서 활성이 아니다.
// <768은 상단 가로 스크롤 칩 줄로 바뀌고 현재 항목을 보이는 위치로 옮긴다(scroll-active 재사용).
// activeTo(FE-73): 하위 화면(주문 상세·클레임 신청·클레임 상세)이 소속 메뉴를 지정하면 그 항목에 aria-current를 붙인다.
// FE-79: 사이드 메뉴 활성 = 흰 면 + shadow-e1 + primary 글자 · <768 칩(44) + 끝에 닿기 전까지만 오른쪽 흐림 · 본문 등장 모션 없음.
defineProps<{ title: string; activeTo?: string }>()

const menuElement = ref<HTMLElement | null>(null)
let stopFollowingActiveItem: (() => void) | null = null
onMounted(() => {
  if (menuElement.value) stopFollowingActiveItem = followActiveItem(menuElement.value)
})
onBeforeUnmount(() => stopFollowingActiveItem?.())
const menuEdges = trackScrollEdges(menuElement)

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
// 메뉴 링크는 aria-current(이동 위치)로 선택을 표시한다 — 링크라 chip의 aria-selected 대신 같은 보라 채움을 aria-current에 준다.
const MENU_LINK =
  'flex shrink-0 items-center whitespace-nowrap transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary max-md:chip max-md:aria-[current=page]:border-primary max-md:aria-[current=page]:bg-primary max-md:aria-[current=page]:text-primary-foreground md:min-h-12 md:rounded-2xl md:px-5 md:text-body md:font-semibold md:text-sub md:hover:text-ink md:aria-[current=page]:bg-white md:aria-[current=page]:text-primary md:aria-[current=page]:shadow-e1'
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="[CONTAINER, 'md:grid md:grid-cols-[200px_minmax(0,1fr)] md:items-start md:gap-10 lg:grid-cols-[240px_minmax(0,1fr)] lg:gap-16']">
      <nav aria-label="마이페이지 메뉴" class="max-md:-mx-5 lg:sticky lg:top-[calc(var(--header-height)_+_24px)]">
        <ul
          ref="menuElement"
          :class="[
            'relative flex gap-2 overflow-x-auto py-1 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory max-md:scroll-px-5 max-md:px-5 max-md:[&>li]:snap-start md:flex-col md:gap-1 md:overflow-visible md:py-0',
            menuEdges.atEnd ? '' : 'max-md:fade-right',
          ]"
        >
          <li v-for="item in MYPAGE_MENU_ITEMS" :key="item.to">
            <NuxtLink :to="item.to" :class="MENU_LINK" v-bind="item.to === activeTo ? { 'aria-current': 'page' } : {}">
              {{ item.label }}
            </NuxtLink>
          </li>
        </ul>
      </nav>

      <div class="mt-6 min-w-0 md:mt-0">
        <!-- 제목 줄 오른쪽 동작(예: 배송지 추가)은 actions slot. -->
        <div class="mb-8 flex items-center justify-between gap-4">
          <h1 class="text-h1 text-ink">{{ title }}</h1>
          <slot name="actions" />
        </div>
        <slot />
      </div>
    </div>
  </div>
</template>
