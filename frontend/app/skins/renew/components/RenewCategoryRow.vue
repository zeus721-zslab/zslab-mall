<script setup lang="ts">
import type { CategorySummary } from '~/types/category'
import { followActiveItem } from '../scroll-active'
import { trackScrollEdges } from '../scroll-edges'

// 최상위 카테고리 메뉴(모바일·태블릿): 한 줄 가로 스크롤. <768은 스크롤바 숨김·스냅·오른쪽 흐림, 현재 카테고리는 보이는 위치로.
// LayoutShell이 고정 헤더 안(기본) 또는 헤더 밖(상품 상세 · FE-99)에 그린다. 테두리·배경은 놓이는 자리에 따라 셸이 준다.
defineProps<{ items: CategorySummary[] }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const MENU_LINK = 'btn btn-tertiary btn-md shrink-0'

// 셸은 페이지 이동 뒤에도 남으므로 현재 카테고리 링크(aria-current)가 바뀔 때마다 다시 맞춘다.
const mobileMenuElement = ref<HTMLElement | null>(null)
let stopFollowingActiveMenu: (() => void) | null = null
onMounted(() => {
  if (mobileMenuElement.value) stopFollowingActiveMenu = followActiveItem(mobileMenuElement.value)
})
onBeforeUnmount(() => stopFollowingActiveMenu?.())
// 오른쪽 흐림은 줄 끝에 닿기 전까지만(FE-77).
const mobileMenuEdges = trackScrollEdges(mobileMenuElement)
</script>

<template>
  <nav aria-label="카테고리" class="border-line lg:hidden">
    <ul
      ref="mobileMenuElement"
      :class="[
        CONTAINER,
        'relative flex gap-1 overflow-x-auto py-1 max-md:scrollbar-none max-md:snap-x max-md:snap-mandatory max-md:scroll-px-5 max-md:[&>li]:snap-start',
        mobileMenuEdges.atEnd ? '' : 'max-md:fade-right',
      ]"
    >
      <li><NuxtLink to="/products" :class="MENU_LINK">전체</NuxtLink></li>
      <li v-for="category in items" :key="category.categoryId">
        <NuxtLink :to="`/categories/${category.categoryId}`" :class="MENU_LINK">{{ category.displayName }}</NuxtLink>
      </li>
    </ul>
  </nav>
</template>
