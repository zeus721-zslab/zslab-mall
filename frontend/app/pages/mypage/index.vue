<script setup lang="ts">
import type { MypageMenu, MypagePageVm } from '~/skins/contracts/mypage'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

// 허브 메뉴(FE-13). 로그아웃은 AppHeader가 담당하므로 여기 중복 배치하지 않는다.
const menus: MypageMenu[] = [
  { to: '/mypage/profile', label: '프로필', description: '이름·연락처 확인 및 수정' },
  { to: '/mypage/password', label: '비밀번호 변경', description: '로그인 비밀번호 변경' },
  { to: '/mypage/addresses', label: '배송지 관리', description: '주소록 추가·수정·삭제' },
  { to: '/orders', label: '주문 내역', description: '주문·취소·반품·교환 진행 상태 확인' },
  { to: '/mypage/withdraw', label: '회원 탈퇴', description: '계정 탈퇴' },
]

useSeoMeta({ title: '마이페이지 · zslab-mall', description: 'zslab-mall 마이페이지' })

const vm: MypagePageVm = reactive({ menus })
</script>

<template>
  <component :is="useSkinView('MypageView')" :vm="vm" />
</template>
