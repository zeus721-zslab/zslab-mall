<script setup lang="ts">
// 리뷰 수정(Track 106-1 PR2). BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
// 작성자 단건 조회로 초기값을 채우고(숨김 리뷰는 수정 불가 안내), 저장은 전체 교체(PUT)다. 폼 상태·제출은 useReviewFormPage가 가진다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const vm = useReviewFormPage({ mode: 'edit', reviewPublicId: route.params.reviewPublicId as string })

useSeoMeta({ title: '리뷰 수정 · zslab-mall', description: 'zslab-mall 상품 리뷰 수정' })
</script>

<template>
  <component :is="useSkinView('ReviewFormView')" :vm="vm" />
</template>
