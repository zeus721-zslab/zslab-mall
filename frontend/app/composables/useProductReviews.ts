import { BUYER_ROLE } from '~/lib/constants/auth'
import { REVIEW_KEYWORD_GROUPS, REVIEW_KEYWORD_GROUP_LABELS, REVIEW_PAGE_SIZE, type ReviewSort } from '~/lib/constants/review'
import type { PagedResponse } from '~/types/order'
import type { ReviewHelpfulResult, ReviewItem, ReviewListFilter, ReviewSummary } from '~/types/review'
import type { ProductReviewsVm, ReviewBar } from '~/skins/contracts/product-reviews'

const PERCENT = 100

function percentText(ratio: number): string {
  return `${Math.round(ratio * PERCENT)}%`
}

/**
 * 상품 상세 리뷰 섹션(Track 106-1 PR2). 페이지가 스킨이 productReviews를 선언했을 때만 호출한다.
 * 요약·키워드 선택지·목록 첫 페이지는 useAsyncData로 SSR에서 받는다(hydration 일치). 목록은 구매자 래퍼로 불러 SSR에서도 쿠키가 실린다 —
 * 로그인 구매자면 helpedByMe가 채워진다(BE는 무효 쿠키를 익명으로 둔다 · 공개 경로 401 없음).
 * 더보기로 받은 페이지와 도움됐어요 결과는 로컬 상태로 겹쳐 보인다(useAsyncData 데이터는 얕은 ref라 항목을 직접 바꾸지 않는다).
 */
export function useProductReviews(productPublicId: string, productDetail: ReturnType<typeof useProductDetail>): ProductReviewsVm {
  const api = useBuyerApi()
  const route = useRoute()
  const auth = useAuthStore()
  const { setHelpful } = useReviewActions()
  const listPath: string = `/v1/products/${productPublicId}/reviews`

  const filter = reactive<ReviewListFilter>({ photoOnly: false, keyword: null, option: null, sort: 'HELPFUL' })

  function queryOf(page: number) {
    return {
      photoOnly: filter.photoOnly || undefined,
      keyword: filter.keyword ?? undefined,
      option: filter.option ?? undefined,
      sort: filter.sort,
      page,
      size: REVIEW_PAGE_SIZE,
    }
  }
  const filterKey = computed(() => JSON.stringify(queryOf(0)))

  const summary = useAsyncData(`product-review-summary:${productPublicId}`, () => api<ReviewSummary>(`${listPath}/summary`))
  const keywordOptions = useReviewKeywordOptions(productPublicId, { immediate: true })
  const firstPage = useAsyncData(
    `product-reviews:${productPublicId}`,
    () => api<PagedResponse<ReviewItem>>(listPath, { query: queryOf(0) }),
    { watch: [filterKey] },
  )

  // 더보기로 이어 받은 항목 · 다음 페이지 번호 · 마지막 응답의 hasNext(null = 아직 더보기 전 → 첫 페이지 값).
  const moreItems = ref<ReviewItem[]>([])
  const nextPage = ref(1)
  const moreHasNext = ref<boolean | null>(null)
  const loadingMore = ref(false)
  watch(filterKey, () => {
    moreItems.value = []
    nextPage.value = 1
    moreHasNext.value = null
  })

  const helpfulOverrides = ref<Record<string, ReviewHelpfulResult>>({})
  const ownReviewIds = ref<string[]>([])
  const helpfulPendingId = ref<string | null>(null)
  const helpfulNotice = ref<{ reviewId: string; text: string } | null>(null)

  const items = computed<ReviewItem[]>(() =>
    [...(firstPage.data.value?.items ?? []), ...moreItems.value].map((item) => {
      const override = helpfulOverrides.value[item.reviewId]
      return override ? { ...item, helpedByMe: override.helped, helpfulCount: override.helpfulCount } : item
    }),
  )
  const hasNext = computed(() => moreHasNext.value ?? firstPage.data.value?.hasNext ?? false)

  async function loadMore(): Promise<void> {
    if (loadingMore.value || !hasNext.value) return
    loadingMore.value = true
    const requestedKey = filterKey.value
    try {
      const response = await api<PagedResponse<ReviewItem>>(listPath, { query: queryOf(nextPage.value) })
      // 받는 사이 필터가 바뀌었으면 옛 조건의 결과는 버린다.
      if (requestedKey !== filterKey.value) return
      moreItems.value = [...moreItems.value, ...response.items]
      nextPage.value += 1
      moreHasNext.value = response.hasNext
    } catch (loadError) {
      // 더보기 실패는 버튼을 그대로 두어 다시 누르게 한다 — 원인만 남긴다.
      console.warn('[review] 리뷰 더보기 실패', loadError)
    } finally {
      loadingMore.value = false
    }
  }

  const ratingBars = computed<ReviewBar[]>(() => {
    const loaded = summary.data.value
    if (!loaded || loaded.reviewCount === 0) return []
    const most = Math.max(...loaded.ratingDistribution.map((entry) => entry.count))
    return loaded.ratingDistribution.map((entry) => ({
      key: String(entry.rating),
      label: `${entry.rating}점`,
      ratio: entry.count / loaded.reviewCount,
      valueText: percentText(entry.count / loaded.reviewCount),
      strong: entry.count > 0 && entry.count === most,
    }))
  })

  // 키워드 집계: 묶음마다 가장 많이 고른 키워드 하나 · 비율 = 그 키워드를 고른 리뷰 / 공개 리뷰 수. 묶음은 키워드 선택지의 groupCode로 안다.
  const keywordBars = computed<ReviewBar[]>(() => {
    const loaded = summary.data.value
    if (!loaded || loaded.reviewCount === 0) return []
    const groupOf = new Map((keywordOptions.data.value ?? []).map((option) => [option.code, option.groupCode]))
    return REVIEW_KEYWORD_GROUPS.flatMap((group) => {
      const top = loaded.keywords.find((keyword) => groupOf.get(keyword.code) === group)
      if (!top) return []
      const ratio = top.count / loaded.reviewCount
      return [{ key: top.code, label: `${REVIEW_KEYWORD_GROUP_LABELS[group]} · ${top.label}`, ratio, valueText: percentText(ratio), strong: false }]
    })
  })

  // 옵션 필터 선택지: 상세의 variant 옵션을 BE 스냅샷과 같은 형식("그룹: 값 / 그룹: 값" · OptionLabelResolver)으로 만든다. 단순상품은 없다.
  const optionChoices = computed<string[]>(() => {
    const labels = (productDetail.data.value?.variants ?? [])
      .map((variant) => variant.options.map((option) => `${option.groupName}: ${option.value}`).join(' / '))
      .filter((label) => label.length > 0)
    return [...new Set(labels)]
  })

  function setPhotoOnly(photoOnly: boolean): void {
    filter.photoOnly = photoOnly
  }
  function setKeyword(code: string | null): void {
    filter.keyword = code
  }
  function setOption(label: string | null): void {
    filter.option = label
  }
  function setSort(sort: ReviewSort): void {
    filter.sort = sort
  }
  function resetFilter(): void {
    filter.photoOnly = false
    filter.keyword = null
    filter.option = null
  }

  async function toggleHelpful(item: ReviewItem): Promise<void> {
    if (helpfulPendingId.value !== null || item.writtenByMe === true || ownReviewIds.value.includes(item.reviewId)) return
    if (!auth.isAuthenticated || auth.role !== BUYER_ROLE) {
      await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
      return
    }
    helpfulPendingId.value = item.reviewId
    helpfulNotice.value = null
    try {
      const result = await setHelpful(item.reviewId, !item.helpedByMe)
      helpfulOverrides.value = { ...helpfulOverrides.value, [item.reviewId]: result }
    } catch (toggleError) {
      const statusCode = (toggleError as { statusCode?: number }).statusCode
      if (statusCode === 401) {
        await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
        return
      }
      if (statusCode === 422) {
        // 방어용: writtenByMe로 미리 잠그지만, 목록을 받은 뒤 로그인한 경우처럼 값이 없을 때 BE 422(본인 리뷰)로 잠근다.
        ownReviewIds.value = [...ownReviewIds.value, item.reviewId]
        helpfulNotice.value = { reviewId: item.reviewId, text: '내가 쓴 리뷰에는 누를 수 없어요.' }
        return
      }
      helpfulNotice.value = {
        reviewId: item.reviewId,
        text: statusCode === 404 ? '지금은 볼 수 없는 리뷰입니다.' : '잠시 후 다시 시도해 주세요.',
      }
    } finally {
      helpfulPendingId.value = null
    }
  }

  async function retry(): Promise<void> {
    await Promise.all([summary.refresh(), firstPage.refresh()])
  }

  return reactive({
    summary: computed(() => (summary.error.value ? null : summary.data.value ?? null)),
    ratingBars,
    keywordBars,
    recentPhotoThumbnails: computed(() => (summary.data.value?.recentPhotos ?? []).map((photo) => photo.thumbnailUrl)),
    recentPhotoUrls: computed(() => (summary.data.value?.recentPhotos ?? []).map((photo) => photo.url)),
    items,
    totalCount: computed(() => firstPage.data.value?.totalCount ?? 0),
    pending: computed(() => firstPage.status.value === 'pending' && !firstPage.data.value),
    failed: computed(() => firstPage.status.value === 'error'),
    retry,
    hasNext,
    loadingMore,
    loadMore,
    filter,
    isFiltered: computed(() => filter.photoOnly || filter.keyword !== null || filter.option !== null),
    optionChoices,
    setPhotoOnly,
    setKeyword,
    setOption,
    setSort,
    resetFilter,
    toggleHelpful,
    helpfulPendingId,
    ownReviewIds,
    helpfulNotice,
  })
}
