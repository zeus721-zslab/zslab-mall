<script setup lang="ts">
// FE-20: 껍데기 페이지. 본문(정렬·탭·그리드·4상태·무한스크롤)은 ProductListView가 담당한다.
// ?categoryId= 는 FE-05 이래 URL 전용 필터로 남겨 호환을 유지한다(탭은 /categories/[id]로 이동).
const route = useRoute()

const categoryId = computed<number | null>(() => {
  const raw = route.query.categoryId
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null
})

useSeoMeta({
  title: '상품 목록 · zslab-mall',
  description: 'zslab-mall 상품 목록. 최신순·가격순·이름순으로 둘러보세요.',
})
</script>

<template>
  <ProductListView title="상품 목록" :category-id="categoryId" />
</template>
