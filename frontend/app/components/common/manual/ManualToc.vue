<script setup lang="ts">
import type { ManualSection } from '~/types/manual'
import { filterManualToc, manualAnchorId } from '~/lib/utils/manual'

/**
 * 매뉴얼 흐름 목차(C8). 검색어로 흐름·단계 제목을 거르고, 현재 읽는 위치(activeId)를 표시한다. 본문이 아직 없는 흐름은
 * "준비 중"으로 보이되 링크가 아니다. 클릭 이동·해시 갱신은 셸이 처리한다(go 이벤트).
 */
const props = defineProps<{ sections: ManualSection[]; activeId: string | null }>()
const emit = defineEmits<{ go: [anchorId: string] }>()

const query = ref('')
const searchId = useId()
const filtered = computed<ManualSection[]>(() => filterManualToc(props.sections, query.value))

// v-model은 IME 조합 중(compositionstart~end)에는 값을 갱신하지 않아 한글 마지막 글자가 다음 입력 때까지 반영되지 않는다 —
// input 이벤트마다 입력란 값을 직접 읽어 조합 중에도 바로 거른다.
function onSearchInput(event: Event): void {
  if (event.target instanceof HTMLInputElement) query.value = event.target.value
}

function isActive(anchorId: string): boolean {
  return props.activeId === anchorId
}

function sectionActive(section: ManualSection): boolean {
  const anchorId = manualAnchorId(section.id)
  return props.activeId === anchorId || (props.activeId !== null && props.activeId.startsWith(anchorId + '--'))
}
</script>

<template>
  <nav class="manual-toc" aria-label="매뉴얼 목차" data-testid="manual-toc">
    <label :for="searchId" class="manual-toc__search-label">흐름 찾기</label>
    <input
      :id="searchId"
      :value="query"
      type="search"
      class="manual-toc__search"
      placeholder="예: 송장, 일괄 승인"
      autocomplete="off"
      data-testid="manual-toc-search"
      @input="onSearchInput"
    >
    <p v-if="filtered.length === 0" class="manual-toc__empty" data-testid="manual-toc-empty">맞는 흐름이 없습니다. 다른 말로 찾아보세요.</p>
    <ul v-else class="manual-toc__sections">
      <li v-for="section in filtered" :key="section.id" class="manual-toc__section" :data-testid="`manual-toc-section-${section.id}`">
        <a
          v-if="section.steps.length > 0"
          :href="`#${manualAnchorId(section.id)}`"
          class="manual-toc__section-link"
          :class="{ 'manual-toc__section-link--current': sectionActive(section) }"
          :aria-current="isActive(manualAnchorId(section.id)) ? 'location' : undefined"
          @click.prevent="emit('go', manualAnchorId(section.id))"
        >{{ section.title }}</a>
        <span v-else class="manual-toc__section-pending">{{ section.title }}<span class="manual-toc__badge">준비 중</span></span>
        <ol v-if="section.steps.length > 0" class="manual-toc__steps">
          <li v-for="step in section.steps" :key="step.id">
            <a
              :href="`#${manualAnchorId(section.id, step.id)}`"
              class="manual-toc__step-link"
              :class="{ 'manual-toc__step-link--active': isActive(manualAnchorId(section.id, step.id)) }"
              :aria-current="isActive(manualAnchorId(section.id, step.id)) ? 'location' : undefined"
              :data-testid="`manual-toc-step-${section.id}-${step.id}`"
              @click.prevent="emit('go', manualAnchorId(section.id, step.id))"
            >{{ step.title }}</a>
          </li>
        </ol>
      </li>
    </ul>
  </nav>
</template>

<style scoped>
.manual-toc {
  font-size: 14px;
}

.manual-toc__search-label {
  display: block;
  margin-bottom: 6px;
  color: #52525b;
  font-size: 12.5px;
  font-weight: 600;
}

.manual-toc__search {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: 1px solid #d4d4d8;
  border-radius: 8px;
  background: #fff;
  color: #18181b;
  font-size: 14px;
}

.manual-toc__search:focus-visible {
  border-color: rgb(var(--v-theme-primary));
  outline: 2px solid rgba(var(--v-theme-primary), 0.25);
  outline-offset: 0;
}

.manual-toc__empty {
  margin: 14px 0 0;
  color: #71717a;
  font-size: 13px;
}

.manual-toc__sections {
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.manual-toc__section + .manual-toc__section {
  margin-top: 4px;
}

.manual-toc__section-link,
.manual-toc__section-pending {
  display: block;
  padding: 7px 8px;
  border-radius: 6px;
  color: #18181b;
  font-weight: 650;
  line-height: 20px;
  text-decoration: none;
}

.manual-toc__section-link--current {
  color: rgb(var(--v-theme-primary));
}

.manual-toc__section-pending {
  color: #a1a1aa;
  font-weight: 500;
}

.manual-toc__badge {
  margin-left: 8px;
  color: #a1a1aa;
  font-size: 11.5px;
  font-weight: 500;
}

.manual-toc__steps {
  position: relative;
  margin: 0 0 6px 15px;
  padding: 0 0 0 12px;
  border-left: 1px solid #e4e4e7;
  list-style: none;
}

.manual-toc__step-link {
  position: relative;
  display: block;
  padding: 5px 8px;
  border-radius: 6px;
  color: #52525b;
  font-size: 13.5px;
  line-height: 20px;
  text-decoration: none;
}

/* 지금 읽는 단계: 목차 세로선 위 점 + 강조색. */
.manual-toc__step-link--active {
  color: rgb(var(--v-theme-primary));
  font-weight: 650;
}

.manual-toc__step-link--active::before {
  content: '';
  position: absolute;
  top: 50%;
  left: -16px;
  width: 7px;
  height: 7px;
  margin-top: -3.5px;
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
}

.manual-toc__section-link:hover,
.manual-toc__step-link:hover {
  background: #f4f4f5;
}

.manual-toc__section-link:focus-visible,
.manual-toc__step-link:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 0;
}
</style>
