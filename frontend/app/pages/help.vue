<script setup lang="ts">
// 구매자 도움말(Track 102 FE-64). 자리만 먼저 확보한다 — 안내 본문은 매뉴얼 단계에서 이 화면에 채운다.
// 공개 경로다(로그인 불필요·미들웨어 없음). 임시 비밀번호 세션에서도 열려야 해서 password-change-guard ALLOWED_PATHS에 함께 넣었다.
useSeoMeta({ title: '도움말 · zslab-mall', description: 'zslab-mall 이용 안내' })
</script>

<template>
  <component :is="useSkinView('HelpView')" />
</template>
