<script setup lang="ts">
definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '일반회원 · zslab-mall 관리자' })

// 일반회원 목록(Track 84 FE). 활성(withdrawn_at NULL) BUYER 회원만 보이며 검색·정렬·페이지는 AdminMemberListView가 URL로 관리한다.
</script>

<template>
  <div>
    <AdminPageHeader title="일반회원" description="가입 회원을 검색·조회하고 회원변경에서 정보 수정·탈퇴·임시 비밀번호·등급을 처리합니다." />
    <AdminMemberListView status="ACTIVE" />
  </div>
</template>
