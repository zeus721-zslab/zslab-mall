<script setup lang="ts">
// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

// 허브 메뉴(FE-13). 로그아웃은 AppHeader가 담당하므로 여기 중복 배치하지 않는다.
const menus: { to: string; label: string; description: string }[] = [
  { to: '/mypage/profile', label: '프로필', description: '이름·연락처 확인 및 수정' },
  { to: '/mypage/password', label: '비밀번호 변경', description: '로그인 비밀번호 변경' },
  { to: '/mypage/addresses', label: '배송지 관리', description: '주소록 추가·수정·삭제' },
  { to: '/mypage/withdraw', label: '회원 탈퇴', description: '계정 탈퇴' },
]

useSeoMeta({ title: '마이페이지 · zslab-mall', description: 'zslab-mall 마이페이지' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">마이페이지</h1>

      <ul class="space-y-3">
        <li v-for="menu in menus" :key="menu.to">
          <NuxtLink
            :to="menu.to"
            class="flex items-center justify-between gap-4 rounded-card border border-line p-5 transition duration-normal hover:border-gray-300"
          >
            <div class="min-w-0">
              <p class="text-base font-medium text-ink">{{ menu.label }}</p>
              <p class="mt-1 text-sm text-sub">{{ menu.description }}</p>
            </div>
            <span class="shrink-0 text-sub" aria-hidden="true">›</span>
          </NuxtLink>
        </li>
      </ul>
    </div>
  </div>
</template>
