<script setup lang="ts">
// FE-20: 검색 결과 페이지 껍데기(/search?keyword=). 미들웨어 없음(공개). 카테고리 탭은 숨긴다(검색은 전 카테고리 대상).
// keyword가 trim 후 비면 API를 호출하지 않고 안내 문구만 보인다. ?keyword= 변경은 페이지 재마운트 없이 computed로 전파된다.
const route = useRoute()

const keyword = computed<string>(() => {
  const raw = route.query.keyword
  return typeof raw === 'string' ? raw.trim() : ''
})

const title = computed(() => (keyword.value ? `'${keyword.value}' 검색 결과` : '검색'))

useSeoMeta({
  title: () => `${title.value} · zslab-mall`,
  description: 'zslab-mall 상품 검색 결과.',
})
</script>

<template>
  <ProductListView v-if="keyword" :title="title" :keyword="keyword" :show-category-tabs="false" />
  <div v-else class="py-14 md:py-20">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-medium tracking-tight text-gray-900">{{ title }}</h1>
      <CommonEmptyState message="검색어를 입력하세요" />
    </div>
  </div>
</template>
