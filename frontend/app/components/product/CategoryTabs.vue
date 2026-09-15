<script setup lang="ts">
// 1차 카테고리 탭(FE-20). "전체"(/products) + 루트 카테고리(/categories/[id]). 가로 스크롤·줄바꿈 없음.
// 카테고리 조회 실패·빈 목록이면 "전체"만 남긴다(목록 영역은 영향 없음). 활성 판정은 categoryId 일치, 없으면 "전체".
const props = defineProps<{
  categoryId?: number | null
}>()

const { data: categories, error } = useCategories()

// 조회 실패 시 data가 null이라 빈 배열로 degrade한다. error는 탭에 표시하지 않는다(헤더·목록이 대신 동작).
const categoryItems = computed(() => (error.value ? [] : categories.value ?? []))

const ACTIVE_CLASS = 'border-gray-900 text-gray-900'
const INACTIVE_CLASS = 'border-transparent text-sub hover:text-gray-900'

function tabClass(active: boolean): string {
  return `shrink-0 whitespace-nowrap border-b-2 px-1 pb-2 text-sm font-medium transition duration-200 ${active ? ACTIVE_CLASS : INACTIVE_CLASS}`
}
</script>

<template>
  <nav aria-label="카테고리" data-testid="category-tabs" class="overflow-x-auto">
    <ul class="flex gap-6 border-b border-gray-100">
      <li>
        <NuxtLink
          to="/products"
          :class="tabClass(props.categoryId == null)"
          :aria-current="props.categoryId == null ? 'page' : undefined"
        >
          전체
        </NuxtLink>
      </li>
      <li v-for="category in categoryItems" :key="category.categoryId">
        <NuxtLink
          :to="`/categories/${category.categoryId}`"
          :class="tabClass(props.categoryId === category.categoryId)"
          :aria-current="props.categoryId === category.categoryId ? 'page' : undefined"
        >
          {{ category.displayName }}
        </NuxtLink>
      </li>
    </ul>
  </nav>
</template>
