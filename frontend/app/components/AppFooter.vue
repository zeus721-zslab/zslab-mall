<script setup lang="ts">
// FE-03 정적 푸터. 링크는 자리만 확보하며 경로는 후속 트랙에서 연결한다.
</script>

<template>
  <footer class="border-t border-gray-100 bg-white">
    <div class="mx-auto max-w-[1240px] px-4 py-12 md:px-6">
      <div class="flex flex-col gap-8 md:flex-row md:items-start md:justify-between">
        <div>
          <p class="text-lg font-bold tracking-tight text-gray-900">zslab-mall</p>
          <p class="mt-2 text-sm text-gray-400">쇼핑의 기준</p>
        </div>
        <nav class="flex flex-wrap gap-x-8 gap-y-3 text-sm text-gray-500" aria-label="푸터">
          <a href="#" class="transition duration-200 hover:text-gray-900">회사소개</a>
          <a href="#" class="transition duration-200 hover:text-gray-900">이용약관</a>
          <a href="#" class="transition duration-200 hover:text-gray-900">개인정보처리방침</a>
          <a href="#" class="transition duration-200 hover:text-gray-900">고객센터</a>
        </nav>
      </div>
      <p class="mt-10 border-t border-gray-50 pt-6 text-xs text-gray-400">
        © 2026 zslab-mall. All rights reserved.
      </p>
    </div>
  </footer>
</template>
