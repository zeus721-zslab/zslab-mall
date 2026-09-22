import type {
  SellerImageUploadResponse,
  SellerProductDetail,
  SellerProductListQuery,
  SellerProductListResponse,
  SellerProductRegistrationResponse,
} from '#layers/seller/app/types/seller-product'
import type {
  SellerCreateRequestBody,
  SellerImagesRequestBody,
  SellerUpdateRequestBody,
  SellerVariantsRequestBody,
} from '#layers/seller/app/lib/seller-product-form'
import type { SellerSaleAction } from '#layers/seller/app/lib/constants/seller-product'
import { toSellerProductApiParams } from '#layers/seller/app/lib/seller-product-query'
import { toSaleStatusTarget } from '#layers/seller/app/lib/seller-product-sale-status'

/**
 * 셀러 상품 API 호출 모음(Track 90-C-3 조회 + 90-C-4 등록·수정·업로드). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기)
 * 경유이며 상태(로딩·에러)는 호출부(페이지·폼)가 소유한다. 판매중지·재판매·상품 단위 수동 품절은 셀러가 직접 처리한다(Track 96-5·D-206).
 * 승인·거부·삭제·재고 delta는 셀러에게 없다(관리자 소관·BE 계약).
 */
export function useSellerProducts() {
  const api = useSellerApi()

  function list(query: SellerProductListQuery): Promise<SellerProductListResponse> {
    return api<SellerProductListResponse>('/v1/seller/products', { query: toSellerProductApiParams(query) })
  }

  /** 상세(이미지·옵션·variant·재고). 타 셀러·미존재·삭제 404(PRODUCT_NOT_FOUND)는 throw. */
  function detail(productPublicId: string): Promise<SellerProductDetail> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다.
    const path: string = `/v1/seller/products/${productPublicId}`
    return api<SellerProductDetail>(path)
  }

  // ---------- 90-C-4 등록·수정 ----------

  /** 등록(옵션+variant+초기재고·PENDING 생성·이미지 없음). 카테고리 404·옵션 조합 중복 409·정지 셀러 403은 throw. */
  function create(body: SellerCreateRequestBody): Promise<SellerProductRegistrationResponse> {
    return api<SellerProductRegistrationResponse>('/v1/seller/products', { method: 'POST', body })
  }

  /** 기본정보 수정(categoryId·name·description·basePrice). 타 셀러 404·카테고리 404·정지 셀러 403은 throw. */
  function update(productPublicId: string, body: SellerUpdateRequestBody): Promise<SellerProductDetail> {
    const path: string = `/v1/seller/products/${productPublicId}`
    return api<SellerProductDetail>(path, { method: 'PUT', body })
  }

  /** 이미지 메타 전체 치환(순서·대표·유형·미포함 soft-delete). 서버 미발급 URL 400·imageId 404는 throw. */
  function replaceImages(productPublicId: string, body: SellerImagesRequestBody): Promise<SellerProductDetail> {
    const path: string = `/v1/seller/products/${productPublicId}/images`
    return api<SellerProductDetail>(path, { method: 'PUT', body })
  }

  /** variant 메타 수정 + 신규 추가(삭제 없음). 옵션값 미존재 400·조합 중복 409·variant 404는 throw. */
  function replaceVariants(productPublicId: string, body: SellerVariantsRequestBody): Promise<SellerProductDetail> {
    const path: string = `/v1/seller/products/${productPublicId}/variants`
    return api<SellerProductDetail>(path, { method: 'PUT', body })
  }

  /** 이미지 업로드(multipart files[]·셀러 경로). 항상 200·파일별 결과. 413·400·403(정지)은 throw. */
  function uploadImages(files: File[]): Promise<SellerImageUploadResponse> {
    const formData = new FormData()
    files.forEach((file) => formData.append('files', file))
    return api<SellerImageUploadResponse>('/v1/seller/files/images', { method: 'POST', body: formData })
  }

  // ---------- 96-5 판매 상태·품절 셀프 전환 ----------

  /** 판매중지(STOP→STOPPED)·재판매(RESUME→SALE). 관리자 중지 재판매 422 PRODUCT_STOPPED_BY_ADMIN·허용 외 전이 422 PRODUCT_INVALID_STATE·타 셀러 404는 throw. */
  function changeSaleStatus(productPublicId: string, action: SellerSaleAction): Promise<SellerProductDetail> {
    const path: string = `/v1/seller/products/${productPublicId}/sale-status`
    return api<SellerProductDetail>(path, { method: 'POST', body: { status: toSaleStatusTarget(action) } })
  }

  /** 상품 단위 수동 품절 on/off(관리자가 켠 품절도 해제 가능·D3 α). 타 셀러 404·정지 셀러 403은 throw. */
  function changeSoldOut(productPublicId: string, soldOut: boolean): Promise<SellerProductDetail> {
    const path: string = `/v1/seller/products/${productPublicId}/soldout`
    return api<SellerProductDetail>(path, { method: 'PATCH', body: { soldOut } })
  }

  return { list, detail, create, update, replaceImages, replaceVariants, uploadImages, changeSaleStatus, changeSoldOut }
}
