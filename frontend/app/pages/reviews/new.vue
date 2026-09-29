<script setup lang="ts">
// 리뷰 작성(Track 106-1 PR2). BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
// 진입점(주문 상세·목록의 별 5개)이 query로 대상 품목·상품·표시용 이름·누른 별점을 넘긴다. 폼 상태·제출은 useReviewFormPage가 가진다.
definePageMeta({ middleware: 'buyer' })

const vm = useReviewFormPage({ mode: 'create' })

useSeoMeta({ title: '리뷰 쓰기 · zslab-mall', description: 'zslab-mall 상품 리뷰 쓰기' })
</script>

<template>
  <component :is="useSkinView('ReviewFormView')" :vm="vm" />
</template>
