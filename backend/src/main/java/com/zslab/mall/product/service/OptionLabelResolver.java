package com.zslab.mall.product.service;

import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * variant의 option1~3 값을 표시용 옵션 라벨("색상: 블랙 / 사이즈: M")로 해소한다(Track 75·D-164). 장바구니(현재 옵션명)와
 * 주문 생성(order_item 스냅샷)이 공용으로 사용한다.
 *
 * <p>DEFAULT sentinel 그룹(단순상품·{@link ProductRegistrationService#DEFAULT_OPTION_GROUP_NAME})은 라벨에서 제외하며,
 * 제외 결과 조합이 비면 null이다. variant가 가리키는 옵션값·그룹이 조회되지 않아도(테스트 픽스처·정합 깨짐) 예외 없이
 * 해당 조각만 건너뛴다 — 옵션 라벨은 표시 전용이라 주문 생성을 실패시키지 않는다.
 *
 * <p>조회는 variant 목록 전체의 값 id 합집합 → 옵션값 배치 1회 → 그룹 배치 1회로 고정한다(N+1 금지). 그룹 id는
 * LAZY 프록시의 식별자 접근만 사용하므로 추가 SELECT가 발생하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OptionLabelResolver {

    static final String GROUP_VALUE_SEPARATOR = ": ";
    static final String OPTION_SEPARATOR = " / ";

    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;

    /** variant 내부 id → 옵션 라벨(옵션 없음·미해소는 항목 자체를 넣지 않는다 → {@code Map.get}이 null). */
    public Map<Long, String> resolve(Collection<ProductVariant> variants) {
        Set<Long> valueIds = new LinkedHashSet<>();
        for (ProductVariant variant : variants) {
            for (Long valueId : optionValueIds(variant)) {
                if (valueId != null) {
                    valueIds.add(valueId);
                }
            }
        }
        if (valueIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductOptionValue> valueById = productOptionValueRepository.findAllById(valueIds).stream()
                .collect(Collectors.toMap(ProductOptionValue::getId, Function.identity()));
        Set<Long> groupIds = valueById.values().stream()
                .map(value -> value.getOptionGroup().getId())
                .collect(Collectors.toSet());
        Map<Long, String> groupNameById = groupIds.isEmpty()
                ? Map.of()
                : productOptionGroupRepository.findAllById(groupIds).stream()
                        .collect(Collectors.toMap(ProductOptionGroup::getId, ProductOptionGroup::getName));

        Map<Long, String> labelByVariantId = new HashMap<>();
        for (ProductVariant variant : variants) {
            String label = buildLabel(variant, valueById, groupNameById);
            if (label != null) {
                labelByVariantId.put(variant.getId(), label);
            }
        }
        return labelByVariantId;
    }

    private static List<Long> optionValueIds(ProductVariant variant) {
        List<Long> valueIds = new ArrayList<>();
        valueIds.add(variant.getOption1ValueId());
        valueIds.add(variant.getOption2ValueId());
        valueIds.add(variant.getOption3ValueId());
        return valueIds;
    }

    private static String buildLabel(
            ProductVariant variant, Map<Long, ProductOptionValue> valueById, Map<Long, String> groupNameById) {
        List<String> parts = new ArrayList<>();
        for (Long valueId : optionValueIds(variant)) {
            if (valueId == null) {
                continue;
            }
            ProductOptionValue value = valueById.get(valueId);
            if (value == null) {
                continue;
            }
            String groupName = groupNameById.get(value.getOptionGroup().getId());
            if (groupName == null || ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(groupName)) {
                continue;
            }
            parts.add(groupName + GROUP_VALUE_SEPARATOR + value.getValue());
        }
        return parts.isEmpty() ? null : String.join(OPTION_SEPARATOR, parts);
    }
}
