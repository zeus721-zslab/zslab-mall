import type {
  ReviewHelpfulResult,
  ReviewKeywordOption,
  ReviewOwner,
  ReviewPhotoUploadResponse,
  ReviewWriteBody,
} from '~/types/review'

/**
 * 구매자 리뷰 쓰기·본인 조회(Track 106-1 PR2 · BUYER 전용 API). 실패(RFC7807)는 throw해 호출부가 review-error 유틸로 문구를 정한다.
 * 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해 string으로 고정한다(useClaim 선례).
 */
export function useReviewActions() {
  const api = useBuyerApi()

  function create(orderItemId: string, body: ReviewWriteBody): Promise<{ reviewId: string }> {
    return api<{ reviewId: string }>('/v1/reviews', { method: 'POST', body: { orderItemId, ...body } })
  }

  function update(reviewId: string, body: ReviewWriteBody): Promise<void> {
    const path: string = `/v1/reviews/${reviewId}`
    return api<void>(path, { method: 'PUT', body })
  }

  function getOwn(reviewId: string): Promise<ReviewOwner> {
    const path: string = `/v1/reviews/${reviewId}`
    return api<ReviewOwner>(path)
  }

  /** 사진 1장 업로드(BE는 요청당 1장 — 여러 장은 호출부가 순서대로 부른다). 항상 200 · 파일별 결과. */
  function uploadPhoto(file: File): Promise<ReviewPhotoUploadResponse> {
    const formData = new FormData()
    formData.append('files', file)
    return api<ReviewPhotoUploadResponse>('/v1/reviews/attachments', { method: 'POST', body: formData })
  }

  function setHelpful(reviewId: string, helped: boolean): Promise<ReviewHelpfulResult> {
    const path: string = `/v1/reviews/${reviewId}/helpful`
    return api<ReviewHelpfulResult>(path, { method: helped ? 'POST' : 'DELETE' })
  }

  return { create, update, getOwn, uploadPhoto, setHelpful }
}

/** 주문 화면이 보이는 작성자 리뷰 상태(숨김 여부·사유). */
export interface OwnReviewState {
  status: ReviewOwner['status']
  hiddenReason?: string
}

/**
 * 주문 상세의 숨긴 리뷰 사유(Track 106-1 PR2). 숨김 여부는 품목 review.hidden에 있고 사유는 작성자 단건 조회(GET /v1/reviews/{id})에만
 * 있어, 호출부가 숨김 품목의 id만 넘긴다. 브라우저에서만 · 처음 보는 id만 조회한다. 실패하면 사유 없이 배지만 보인다.
 */
export function useOwnReviewStates(reviewIds: Readonly<Ref<string[]>>) {
  const { getOwn } = useReviewActions()
  const states = ref<Record<string, OwnReviewState>>({})
  const requested = new Set<string>()

  async function load(ids: string[]): Promise<void> {
    const fresh = ids.filter((id) => !requested.has(id))
    fresh.forEach((id) => requested.add(id))
    await Promise.all(
      fresh.map(async (id) => {
        try {
          const own = await getOwn(id)
          states.value = { ...states.value, [id]: { status: own.status, hiddenReason: own.hiddenReason } }
        } catch (loadError) {
          // 배지는 보조 정보라 화면을 막지 않는다 — 원인만 남긴다.
          console.warn('[review] 작성자 리뷰 상태 조회 실패', id, loadError)
        }
      }),
    )
  }

  onMounted(() => {
    watch(reviewIds, (ids) => void load(ids), { immediate: true })
  })

  return states
}

/** 작성 폼 키워드 선택지(공개 GET · 기본 세트 ∪ 상품 카테고리 세트). 상품이 비노출이면 404를 error로 준다. */
export function useReviewKeywordOptions(productPublicId: string, options: { immediate: boolean }) {
  const config = useRuntimeConfig()
  return useFetch<ReviewKeywordOption[]>(`/v1/products/${productPublicId}/reviews/keywords`, {
    key: `review-keywords:${productPublicId}`,
    baseURL: import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api',
    immediate: options.immediate,
  })
}
