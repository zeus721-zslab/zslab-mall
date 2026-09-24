<script setup lang="ts">
import type { MypagePageVm } from '~/skins/contracts/mypage'

defineProps<{ vm: MypagePageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">마이페이지</h1>

      <ul class="space-y-3">
        <li v-for="menu in vm.menus" :key="menu.to">
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
