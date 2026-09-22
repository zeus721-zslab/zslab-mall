import type { ProductImageType } from '#layers/admin/app/lib/constants/product'
import type { AdminProductStatus, AdminSaleStopSource } from '#layers/admin/app/lib/constants/product'

/**
 * 관리자 상품 등록·수정 폼 모델(FE-26·신규/수정 공용). BE 응답/요청과 분리된 화면 전용 구조이며 변환은 lib/admin-product-form.ts 순수 함수가
 * 담당한다. 식별자(imageId·optionGroupId·optionValueId·variantPublicId)는 수정 모드에서만 채워지고 신규 항목은 null이다.
 */

export interface ProductFormImage {
  /** 화면 전용 키(드래그 정렬·목록 key). */
  localId: string
  imageId: number | null
  imageUrl: string
  thumbnailUrl: string
  imageType: ProductImageType
  main: boolean
}

export interface ProductFormOptionValue {
  localId: string
  optionValueId: number | null
  value: string
}

export interface ProductFormOptionGroup {
  localId: string
  optionGroupId: number | null
  name: string
  values: ProductFormOptionValue[]
}

/** 조합표 한 행 = variant. optionValueLocalIds는 그룹 순서대로의 값 localId(단일 상품은 빈 배열). */
export interface ProductFormVariant {
  localId: string
  variantPublicId: string | null
  optionValueLocalIds: string[]
  variantCode: string
  additionalPrice: number
  /** 신규는 초기 재고, 기존은 현재 가용 재고(수정 시 delta를 adjust API로 보낸다). */
  stock: number
  /** 기존 variant의 서버 재고(delta 계산 기준·신규는 null). */
  stockOnServer: number | null
  soldOutManual: boolean
  enabled: boolean
  /** 수정 모드에서 값 삭제로 조합에서 빠진 기존 variant(soft-delete 예정) 표시용. */
  removed: boolean
  /** 신규 행(variantPublicId null) 전용 "제외": true면 저장 요청에서 빠진다(생성하지 않음). 기존 행은 항상 false. */
  excluded: boolean
}

export interface ProductForm {
  productPublicId: string | null
  sellerPublicId: string | null
  categoryId: number | null
  name: string
  description: string
  basePrice: number | null
  supplyPrice: number | null
  /** datetime-local 값('yyyy-MM-ddTHH:mm')·빈 문자열=즉시. */
  saleStartAt: string
  /** datetime-local 값·noEndDate=true면 무시. */
  saleEndAt: string
  noEndDate: boolean
  images: ProductFormImage[]
  hasOptions: boolean
  optionGroups: ProductFormOptionGroup[]
  variants: ProductFormVariant[]
  /** 수정 모드 표시 전용(서버 상태). */
  status: AdminProductStatus | null
  /** 수정 모드 표시 전용·STOPPED일 때만(D-206). */
  saleStopSource: AdminSaleStopSource | null
  soldOutManual: boolean
}

/** 필드 단위 에러(클라이언트 검증·BE fieldErrors 공용). 키는 폼 필드 경로(예 name·variants.3.variantCode). */
export type ProductFormErrors = Record<string, string>
