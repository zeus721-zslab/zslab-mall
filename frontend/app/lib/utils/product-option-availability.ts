import type { ProductVariant } from '~/types/product'

/**
 * 옵션 값 품절 판단(FE-70·순수 함수). 이 값과 현재 다른 그룹 선택값을 모두 포함하는 variant 중 구매 가능한 것이 하나도 없으면 품절이다.
 * 선택하지 않은 그룹은 조건에서 빼므로 조합 미완료 상태에서도 동작한다(단일 그룹이면 이 값을 가진 variant만 본다).
 * BE는 판매 가능(SALE) variant만 내리므로 이 값을 가진 variant가 아예 없어도 품절로 본다(골라도 담을 variant가 없다).
 */
export function isOptionValueSoldOut(
  variants: ProductVariant[],
  selectedOptions: Record<string, string>,
  groupName: string,
  value: string,
): boolean {
  return !variants.some(
    (variant) =>
      !variant.soldOut &&
      variant.options.some((option) => option.groupName === groupName && option.value === value) &&
      variant.options.every(
        (option) =>
          option.groupName === groupName ||
          selectedOptions[option.groupName] === undefined ||
          selectedOptions[option.groupName] === option.value,
      ),
  )
}
