import {
  REVIEW_CONTENT_MAX,
  REVIEW_KEYWORD_GROUPS,
  REVIEW_KEYWORD_GROUP_LABELS,
  REVIEW_KEYWORD_MAX,
  REVIEW_PHOTO_MAX,
  REVIEW_RATING_LABELS,
  REVIEW_RATING_MAX,
  REVIEW_RATING_MIN,
} from '~/lib/constants/review'
import { reviewWriteFailure } from '~/lib/utils/review-error'
import type { ReviewFormPhoto, ReviewOwner } from '~/types/review'
import type { ReviewFormPageVm, ReviewKeywordSection } from '~/skins/contracts/review-form'

const ORDER_ITEM_ID_PATTERN = /^oit_[0-9A-Z]{26}$/
const PRODUCT_ID_PATTERN = /^prd_[0-9A-Z]{26}$/
const REVIEW_ID_PATTERN = /^rvw_[0-9A-Z]{26}$/

type ReviewFormTarget = { mode: 'create' } | { mode: 'edit'; reviewPublicId: string }

function queryText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** query rating(1~5 정수)만 받는다 — 그 밖은 비운 채 연다. */
function parseRating(value: unknown): number | null {
  const rating = Number(queryText(value))
  return Number.isInteger(rating) && rating >= REVIEW_RATING_MIN && rating <= REVIEW_RATING_MAX ? rating : null
}

/**
 * 리뷰 작성·수정 페이지 vm(Track 106-1 PR2 · 두 페이지 공용). 진입점(주문 상세·목록)이 넘긴 query로 대상을 받는다 —
 * 작성은 orderItem(oit_)·product(prd_)·표시용 name·option·rating, 수정은 경로 reviewPublicId(rvw_) + product·name.
 * 수정은 작성자 단건 조회로 초기값을 채우고(사진은 attachmentId로 유지), 숨김 리뷰면 잠근다(BE도 422).
 * 실패는 review-error 유틸이 코드별 문구로 바꾸고 401만 로그인으로 보낸다.
 */
export function useReviewFormPage(target: ReviewFormTarget): ReviewFormPageVm {
  const route = useRoute()
  const orderItemId = queryText(route.query.orderItem)
  const productPublicId = queryText(route.query.product)
  const productName = queryText(route.query.name)
  const optionLabel = queryText(route.query.option)
  const reviewPublicId = target.mode === 'edit' ? target.reviewPublicId : ''

  const isValidQuery = PRODUCT_ID_PATTERN.test(productPublicId)
    && (target.mode === 'create' ? ORDER_ITEM_ID_PATTERN.test(orderItemId) : REVIEW_ID_PATTERN.test(reviewPublicId))

  const { create, update, uploadPhoto } = useReviewActions()

  const rating = ref<number | null>(target.mode === 'create' ? parseRating(route.query.rating) : null)
  const selectedKeywords = ref<string[]>([])
  const photos = ref<ReviewFormPhoto[]>([])
  const photosBusy = ref(false)
  // 올리지 못한 사진 칸 수 — 남아 있으면 등록을 막는다(빠진 사진이 조용히 제외되지 않게).
  const photosFailed = ref(0)
  const content = ref('')
  const submitting = ref(false)
  const submitted = ref(false)
  const errorMessage = ref('')

  // 키워드 선택지(공개 GET · 기본 세트 ∪ 상품 카테고리 세트). 실패해도 키워드는 선택 항목이라 폼은 그대로 쓴다.
  const keywordOptions = useReviewKeywordOptions(productPublicId, { immediate: isValidQuery })
  const keywordSections = computed<ReviewKeywordSection[]>(() => {
    const options = keywordOptions.data.value ?? []
    return REVIEW_KEYWORD_GROUPS
      .map((group) => ({
        group,
        label: REVIEW_KEYWORD_GROUP_LABELS[group],
        options: options.filter((option) => option.groupCode === group).sort((first, second) => first.sortOrder - second.sortOrder),
      }))
      .filter((section) => section.options.length > 0)
  })
  const keywordLimitReached = computed(() => selectedKeywords.value.length >= REVIEW_KEYWORD_MAX)

  function toggleKeyword(code: string): void {
    if (selectedKeywords.value.includes(code)) {
      selectedKeywords.value = selectedKeywords.value.filter((selected) => selected !== code)
      return
    }
    if (keywordLimitReached.value) return
    selectedKeywords.value = [...selectedKeywords.value, code]
  }

  // 수정: 작성자 단건(숨김 포함). 타인·삭제·미존재는 404.
  const own = useFetch<ReviewOwner>(`/v1/reviews/${reviewPublicId}`, {
    key: `review-own:${reviewPublicId}`,
    $fetch: useBuyerApi(),
    immediate: target.mode === 'edit' && isValidQuery,
    // hydration 중에는 SSR payload를 쓰고(첫 렌더가 SSR과 같은 폼 · 중복 조회 없음), 그 밖(재방문)은 항상 새로 받는다.
    getCachedData: (key, nuxtApp) => (nuxtApp.isHydrating ? nuxtApp.payload.data[key] : undefined),
  })
  const loading = computed(() => target.mode === 'edit' && own.status.value === 'pending')
  const loadError = computed(() => {
    if (target.mode !== 'edit' || !own.error.value) return ''
    return own.error.value.statusCode === 404 ? '리뷰를 찾을 수 없습니다.' : '리뷰를 불러오지 못했습니다.'
  })
  const locked = computed(() => own.data.value?.status === 'HIDDEN')
  const hiddenReason = computed(() => own.data.value?.hiddenReason ?? '')

  watch(
    () => own.data.value,
    (loaded) => {
      if (!loaded) return
      rating.value = loaded.rating
      content.value = loaded.content
      selectedKeywords.value = loaded.keywords.map((keyword) => keyword.code)
      photos.value = loaded.photos.map((photo, index) => ({
        attachmentId: photo.attachmentId,
        previewUrl: photo.thumbnailUrl,
        name: `기존 사진 ${index + 1}`,
      }))
    },
    { immediate: true },
  )

  watch(
    () => own.error.value,
    (loadFailure) => {
      if (loadFailure?.statusCode === 401) navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    },
    { immediate: true },
  )

  const ratingLabel = computed(() => (rating.value === null ? '별점을 선택해 주세요' : REVIEW_RATING_LABELS[rating.value] ?? ''))
  const trimmedLength = computed(() => content.value.trim().length)
  const canSubmit = computed(() =>
    !submitting.value && !photosBusy.value && photosFailed.value === 0 && !locked.value && rating.value !== null
    && trimmedLength.value > 0 && content.value.length <= REVIEW_CONTENT_MAX,
  )

  async function handleSubmit(): Promise<void> {
    if (submitting.value) return
    if (rating.value === null) {
      errorMessage.value = '별점을 선택해 주세요.'
      return
    }
    if (trimmedLength.value === 0) {
      errorMessage.value = '리뷰 내용을 입력해 주세요.'
      return
    }
    if (photosBusy.value) {
      errorMessage.value = '사진을 올리는 중입니다. 잠시 후 등록해 주세요.'
      return
    }
    if (photosFailed.value > 0) {
      errorMessage.value = '올리지 못한 사진이 있습니다. 다시 올리거나 빼 주세요.'
      return
    }
    submitting.value = true
    errorMessage.value = ''
    const body = {
      rating: rating.value,
      keywordCodes: selectedKeywords.value,
      content: content.value.trim(),
      attachmentIds: photos.value.map((photo) => photo.attachmentId),
    }
    try {
      if (target.mode === 'create') await create(orderItemId, body)
      else await update(reviewPublicId, body)
      submitted.value = true
    } catch (submitError) {
      const failure = reviewWriteFailure(submitError)
      if (failure.login) {
        await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
        return
      }
      errorMessage.value = failure.message
    } finally {
      submitting.value = false
    }
  }

  return reactive({
    mode: target.mode,
    isValidQuery,
    loading,
    loadError,
    locked,
    hiddenReason,
    productName,
    optionLabel,
    productPath: `/products/${productPublicId}`,
    rating,
    ratingLabel,
    keywordSections,
    keywordsPending: computed(() => keywordOptions.status.value === 'pending'),
    keywordsFailed: computed(() => keywordOptions.status.value === 'error'),
    retryKeywords: () => keywordOptions.refresh(),
    selectedKeywords,
    toggleKeyword,
    keywordLimitReached,
    photos,
    photosBusy,
    photosFailed,
    uploadPhoto,
    content,
    submitting,
    submitted,
    canSubmit,
    errorMessage,
    handleSubmit,
    CONTENT_MAX: REVIEW_CONTENT_MAX,
    KEYWORD_MAX: REVIEW_KEYWORD_MAX,
    PHOTO_MAX: REVIEW_PHOTO_MAX,
  })
}
