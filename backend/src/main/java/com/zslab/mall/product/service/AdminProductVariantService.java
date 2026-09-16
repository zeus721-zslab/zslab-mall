package com.zslab.mall.product.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.product.controller.request.AdminProductVariantsRequest;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.enums.ProductVariantStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.exception.ProductVariantOptionConflictException;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 variant 전체 치환 서비스(Track 76·D-165 D9 α). 옵션 그룹 구조(그룹 수·이름·슬롯 순서)는 등록 시점 그대로 유지하고,
 * variant 단위로만 (1) 기존 메타 수정 (2) 신규 생성 (3) soft-delete를 수행한다. 옵션 조합 변경은 "신규 생성 + 기존 soft-delete"로
 * 표현한다(order_item FK·cart 스냅샷 보존·조합 UK 정합).
 *
 * <p>신규 variant의 옵션은 (optionGroupId·value)로 지정하며 값이 그룹에 없으면 새 {@link ProductOptionValue}를 만든다. 슬롯 매핑은
 * 등록과 동일하게 그룹 displayOrder 오름차순(option1=첫 그룹)이다. 단순상품(DEFAULT sentinel 그룹만 보유)은 options를 비워야 하며
 * DEFAULT 값으로 해소한다. 조합 중복은 in-memory 선검증(NULL 슬롯은 UK가 발동하지 않음·등록 Service INV-E 정합) + flush 시점
 * {@link DataIntegrityViolationException} 409 변환의 이중 방어다.
 *
 * <p><b>트랩</b>: 3슬롯이 전부 채워진 조합은 soft-delete된 variant도 UK에 남아 있어 같은 조합을 신규 생성하면 409다(하드 삭제 금지·A5).
 * 이 경우 기존 variant를 삭제하지 말고 메타 수정으로 되살리는 것이 운영 대안이다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AdminProductVariantService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final InventoryService inventoryService;
    private final AuditRecorder auditRecorder;

    /**
     * variant 목록을 요청대로 치환한다.
     *
     * @throws ProductNotFoundException 상품 미존재(404)
     * @throws ProductVariantNotFoundException variantPublicId가 이 상품의 활성 variant가 아닐 때(404)
     * @throws IllegalArgumentException 옵션 그룹 불일치·단순상품 옵션 지정·그룹 누락/중복(400)
     * @throws ProductVariantOptionConflictException 동일 옵션 조합 중복(409)
     */
    public void replaceVariants(String publicId, AdminProductVariantsRequest request, AuditContext auditContext) {
        Product product = productRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: publicId=" + publicId));

        List<ProductOptionGroup> groups = productOptionGroupRepository.findByProductId(product.getId()).stream()
                .sorted(Comparator.comparingInt(ProductOptionGroup::getDisplayOrder).thenComparing(ProductOptionGroup::getId))
                .toList();
        boolean simpleProduct = groups.size() == 1
                && ProductRegistrationService.DEFAULT_OPTION_GROUP_NAME.equals(groups.get(0).getName());
        Map<Long, List<ProductOptionValue>> valuesByGroupId = loadValuesByGroupId(groups);

        Map<String, ProductVariant> existingByPublicId = productVariantRepository.findByProductId(product.getId()).stream()
                .collect(Collectors.toMap(ProductVariant::getPublicId, Function.identity()));
        int existingCount = existingByPublicId.size();
        int createdCount = 0;
        // 조합 중복은 in-memory로 선검증한다 — uk_product_variant_options는 option2/3 NULL 조합에서 NULL≠NULL이라 발동하지 않는다
        // (ProductRegistrationService INV-E와 동일 사유). 기준 집합 = 요청에 남는 기존 variant의 조합.
        Set<List<Long>> combinations = new HashSet<>();
        for (AdminProductVariantsRequest.Item item : request.variants()) {
            ProductVariant kept = item.variantPublicId() == null ? null : existingByPublicId.get(item.variantPublicId());
            if (kept != null) {
                combinations.add(Arrays.asList(kept.getOption1ValueId(), kept.getOption2ValueId(), kept.getOption3ValueId()));
            }
        }

        try {
            for (AdminProductVariantsRequest.Item item : request.variants()) {
                if (item.variantPublicId() != null) {
                    ProductVariant existing = existingByPublicId.remove(item.variantPublicId());
                    if (existing == null) {
                        throw new ProductVariantNotFoundException("상품 변형을 찾을 수 없습니다: productPublicId=" + publicId
                                + ", variantPublicId=" + item.variantPublicId());
                    }
                    existing.updateMeta(item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                            ProductVariantStatus.valueOf(item.status()), item.soldoutManual(), item.displayOrder());
                    continue;
                }
                Long[] slots = resolveSlots(item, groups, simpleProduct, valuesByGroupId);
                if (!combinations.add(Arrays.asList(slots))) {
                    throw new ProductVariantOptionConflictException(
                            "동일 옵션 조합의 상품 변형이 이미 존재합니다: variantCode=" + item.variantCode());
                }
                ProductVariant created = productVariantRepository.save(ProductVariant.create(
                        product.getId(), item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                        item.displayOrder(), slots[0], slots[1], slots[2]));
                created.updateMeta(item.variantCode(), item.sellerSku(), item.barcode(), item.additionalPrice(),
                        ProductVariantStatus.valueOf(item.status()), item.soldoutManual(), item.displayOrder());
                inventoryService.initializeInventory(created.getId(), product.getId(), item.initialStock());
                createdCount++;
            }
            // 목록에 남지 않은 기존 variant는 soft-delete(최소 1개 잔존은 DTO @NotEmpty가 보장).
            existingByPublicId.values().forEach(ProductVariant::markDeleted);
            productVariantRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            log.warn("[AdminProductVariant] 옵션 조합 중복 차단(409·uk_product_variant_options) productPublicId={}: {}",
                    publicId, exception.getMostSpecificCause().getMessage());
            throw new ProductVariantOptionConflictException("동일 옵션 조합의 상품 변형이 이미 존재합니다(uk_product_variant_options).");
        }

        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                Map.of("variantCount", existingCount),
                Map.of("variantCount", request.variants().size(), "created", createdCount, "deleted", existingByPublicId.size()));
        log.info("[AdminProductVariant] variant 치환 productPublicId={} total={} created={} deleted={}",
                publicId, request.variants().size(), createdCount, existingByPublicId.size());
    }

    private Map<Long, List<ProductOptionValue>> loadValuesByGroupId(List<ProductOptionGroup> groups) {
        List<Long> groupIds = groups.stream().map(ProductOptionGroup::getId).toList();
        Map<Long, List<ProductOptionValue>> valuesByGroupId = new HashMap<>();
        if (groupIds.isEmpty()) {
            return valuesByGroupId;
        }
        for (ProductOptionValue value : productOptionValueRepository.findByOptionGroupIdIn(groupIds)) {
            valuesByGroupId.computeIfAbsent(value.getOptionGroup().getId(), key -> new ArrayList<>()).add(value);
        }
        return valuesByGroupId;
    }

    /**
     * 신규 variant의 options를 option1~3 valueId 슬롯으로 해소한다. 그룹 displayOrder 순서가 슬롯 순서이며, 상품의 모든(비-DEFAULT)
     * 그룹을 정확히 한 번씩 지정해야 한다. 값이 그룹에 없으면 새로 만든다(displayOrder=현재 최대+1).
     */
    private Long[] resolveSlots(
            AdminProductVariantsRequest.Item item,
            List<ProductOptionGroup> groups,
            boolean simpleProduct,
            Map<Long, List<ProductOptionValue>> valuesByGroupId) {
        List<AdminProductVariantsRequest.Option> options = item.options() == null ? List.of() : item.options();
        Long[] slots = new Long[3];
        if (simpleProduct) {
            if (!options.isEmpty()) {
                throw new IllegalArgumentException("단순상품(옵션 없음)의 variant에는 options를 지정할 수 없습니다.");
            }
            ProductOptionGroup defaultGroup = groups.get(0);
            slots[0] = valuesByGroupId.getOrDefault(defaultGroup.getId(), List.of()).stream()
                    .findFirst()
                    .map(ProductOptionValue::getId)
                    .orElseThrow(() -> new IllegalStateException("DEFAULT 옵션값이 없습니다: groupId=" + defaultGroup.getId()));
            return slots;
        }
        if (options.size() != groups.size()) {
            throw new IllegalArgumentException("신규 variant는 상품의 옵션 그룹 " + groups.size() + "개를 모두 지정해야 합니다.");
        }
        Map<Long, String> valueByGroupId = new HashMap<>();
        for (AdminProductVariantsRequest.Option option : options) {
            if (valueByGroupId.put(option.optionGroupId(), option.value()) != null) {
                throw new IllegalArgumentException("옵션 그룹이 중복 지정되었습니다: optionGroupId=" + option.optionGroupId());
            }
        }
        for (int slot = 0; slot < groups.size(); slot++) {
            ProductOptionGroup group = groups.get(slot);
            String value = valueByGroupId.get(group.getId());
            if (value == null) {
                throw new IllegalArgumentException("이 상품의 옵션 그룹이 아니거나 누락되었습니다: optionGroupId=" + group.getId());
            }
            slots[slot] = findOrCreateValue(group, value, valuesByGroupId).getId();
        }
        return slots;
    }

    private ProductOptionValue findOrCreateValue(
            ProductOptionGroup group, String value, Map<Long, List<ProductOptionValue>> valuesByGroupId) {
        List<ProductOptionValue> values = valuesByGroupId.computeIfAbsent(group.getId(), key -> new ArrayList<>());
        for (ProductOptionValue candidate : values) {
            if (candidate.getValue().equals(value)) {
                return candidate;
            }
        }
        int nextOrder = values.stream().mapToInt(ProductOptionValue::getDisplayOrder).max().orElse(-1) + 1;
        ProductOptionValue created = productOptionValueRepository.save(ProductOptionValue.create(group, value, nextOrder));
        values.add(created);
        log.info("[AdminProductVariant] 옵션값 신규 생성 groupId={} value={}", group.getId(), value);
        return created;
    }
}
