import type { SellerProductImageType, SellerProductStatus } from '#layers/seller/app/lib/constants/seller-product'

/**
 * 셀러 상품 등록·수정 폼 모델(Track 90-C-4·관리자 admin-product-form 복제·축소). BE 응답/요청과 분리된 화면 전용 구조이며 변환은
 * lib/seller-product-form.ts 순수 함수가 담당한다. 관리자와 달리 셀러 지정·공급가·판매기간·상품 상태 변경은 없고(BE 계약 6), 기존 variant 재고는
 * 읽기 전용(stockOnServer·계약 1)이며 신규 variant만 initialStock을 가진다. variant 삭제·removed 개념이 없다(계약 3·비활성화=HIDDEN).
 */

export interface SellerFormImage {
  /** 화면 전용 키(드래그 정렬·목록 key). */
  localId: string
  imageId: number | null
  /** 업로드 API가 발급한 서버 URL만 들어온다(직접 입력 없음·D-174). */
  imageUrl: string
  thumbnailUrl: string
  imageType: SellerProductImageType
  main: boolean
}

export interface SellerFormOptionValue {
  localId: string
  optionValueId: number | null
  value: string
}

export interface SellerFormOptionGroup {
  localId: string
  optionGroupId: number | null
  name: string
  values: SellerFormOptionValue[]
}

/** 기존 variant의 서버 재고(읽기 전용·수정은 재고 화면 mark-inbound/outbound). */
export interface SellerFormVariantStock {
  onHand: number
  reserved: number
  available: number
}

/** 조합표 한 행 = variant. optionValueLocalIds는 그룹 순서대로의 값 localId(단일 상품은 빈 배열). */
export interface SellerFormVariant {
  localId: string
  /** 기존 행이면 public_id, 신규(미생성) 행이면 null. */
  variantPublicId: string | null
  optionValueLocalIds: string[]
  variantCode: string
  sellerSku: string
  barcode: string
  additionalPrice: number
  /** 신규 행 전용 초기 재고(기존 행은 무시). */
  initialStock: number
  /** 기존 행 전용 서버 재고(신규는 null). 폼에서 바꾸지 않는다. */
  stockOnServer: SellerFormVariantStock | null
  soldoutManual: boolean
  /** true=SALE · false=HIDDEN(비활성화·삭제 대신). 수정 모드에서만 토글. */
  enabled: boolean
  /** 신규 행 전용: true면 저장 요청에서 빠진다(등록="제외"·수정="추가 안 함"). 기존 행은 항상 false. */
  excluded: boolean
}

export interface SellerProductForm {
  productPublicId: string | null
  categoryId: number | null
  name: string
  description: string
  basePrice: number | null
  images: SellerFormImage[]
  hasOptions: boolean
  optionGroups: SellerFormOptionGroup[]
  variants: SellerFormVariant[]
  /** 수정 모드 표시 전용(서버 상태·변경 불가). */
  status: SellerProductStatus | null
  soldoutManual: boolean
}

/** 필드 단위 에러(클라이언트 검증·BE fieldErrors 공용). 키는 폼 필드 경로(예 name·variants.3.variantCode). */
export type SellerProductFormErrors = Record<string, string>

export type SellerProductFormMode = 'create' | 'edit'
