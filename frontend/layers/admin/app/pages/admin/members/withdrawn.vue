<script setup lang="ts">
definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '탈퇴회원 · zslab-mall 관리자' })

// 탈퇴회원 목록(Track 84 FE). withdrawn_at이 있는 BUYER 회원만 보이며 탈퇴일 컬럼이 추가된다. 탈퇴 회원은 상세에서 액션이 비활성이다.
</script>

<template>
  <div>
    <AdminPageHeader title="탈퇴회원" description="탈퇴 처리된 회원을 조회합니다. 탈퇴 회원은 정보 수정·임시 비밀번호·등급 변경이 불가합니다." />
    <AdminMemberListView status="WITHDRAWN" />
  </div>
</template>
