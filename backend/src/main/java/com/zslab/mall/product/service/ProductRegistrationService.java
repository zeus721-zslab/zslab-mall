package com.zslab.mall.product.service;

import com.zslab.mall.category.exception.CategoryNotFoundException;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.product.controller.request.ProductOptionGroupRequest;
import com.zslab.mall.product.controller.request.ProductOptionValueRequest;
import com.zslab.mall.product.controller.request.ProductRegistrationRequest;
import com.zslab.mall.product.controller.request.ProductVariantRequest;
import com.zslab.mall.product.controller.response.ProductRegistrationResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
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
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록 오케스트레이션 Application Service(Track 39 P4·seller 주도). 단일 요청으로 Product·OptionGroup·OptionValue·
 * ProductVariant·초기 재고(Inventory)를 하나의 트랜잭션에 원자 생성한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p><b>검증 순서(α·저장 전 in-memory 선검증)</b>: categoryId 존재(404) → INV-A~D 전량 in-memory 검증(400) → 통과분만 저장.
 * 교차 엔티티·요청 구조 정합(INV-A~D·categoryId)은 본 Service가, 자기 엔티티 invariant(길이·null·음수)는 각 팩토리가
 * 검증한다(M7 검증 경계). sellerId는 인증 컨텍스트에서 해소돼 Controller가 주입하며(P5 배선·D-92 Q3 진입부 패턴), 본
 * Service는 resolve를 직접 호출하지 않는다(테스트 용이성).
 *
 * <p><b>임시키(TempKey) 해소</b>: 요청의 optionKeys(클라이언트 지정 문자열)를 저장된 OptionValue의 DB id로 해소해
 * variant의 option1~3_value_id에 매핑한다. 매핑 순서는 variant의 optionKeys 순서가 아니라 <b>OptionGroup의 displayOrder
 * 오름차순</b> 기준이다(option1=첫 그룹값·option2=둘째·option3=셋째). 중복 옵션 조합(uk_product_variant_options)은 사전
 * 조회 없이 flush 시점 {@link DataIntegrityViolationException}을 409로 변환한다(R5-1/M6·DB 제약 최종 방어선·
 * {@code SellerProvisioningService} saveAndFlush 패턴 정합).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProductRegistrationService {

    /** OptionGroup 상한(INV-D·PRD-4). option1~3_value_id 3슬롯과 정합. */
    private static final int MAX_OPTION_GROUPS = 3;

    // 단순상품(옵션 미지정) sentinel(α′). option1_value_id NOT NULL을 충족하기 위해 DEFAULT 1조를 합성한다. 현재 상품 조회
    // API가 없어 어떤 응답에도 노출되지 않으며, 향후 카탈로그 read 도입 시 "DEFAULT" 옵션은 표시 필터 대상이다(정찰 확인).
    private static final String DEFAULT_OPTION_GROUP_NAME = "DEFAULT";
    private static final String DEFAULT_OPTION_VALUE = "DEFAULT";
    private static final String DEFAULT_OPTION_TEMP_KEY = "__default__"; // 요청 내부 임시키(미영속·합성 전용)
    private static final int DEFAULT_DISPLAY_ORDER = 0;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryService inventoryService;

    /**
     * 상품을 등록한다. 성공 시 Product·OptionGroup·OptionValue·ProductVariant·초기 재고를 원자 생성하고 생성된 식별자를 반환한다.
     *
     * @param sellerId 인증 컨텍스트에서 해소된 판매자 식별자(Controller 주입·P5)
     * @param request 상품 등록 요청(중첩 optionGroups·variants)
     * @return 생성된 product public_id + variant public_id 목록
     * @throws CategoryNotFoundException categoryId에 해당하는 Category가 없을 때(404)
     * @throws IllegalArgumentException INV-A~D·임시키 정합 위반 시(400)
     * @throws ProductVariantOptionConflictException 동일 옵션 조합 변형 중복 시(409·uk_product_variant_options)
     */
    public ProductRegistrationResponse registerProduct(Long sellerId, ProductRegistrationRequest request) {
        // 1. categoryId 존재 검증(404). id만 확인하면 되므로 엔티티 로드 없이 existsById 사용(SellerProvisioning 선례).
        if (!categoryRepository.existsById(request.categoryId())) {
            throw new CategoryNotFoundException("상품 카테고리가 존재하지 않습니다: categoryId=" + request.categoryId());
        }

        // 2. 단순상품(optionGroups 빈 배열) 지원: DEFAULT 옵션 1조를 합성해 옵션 있는 경로와 동일 파이프라인으로 통합한다(α′).
        ProductRegistrationRequest effectiveRequest = synthesizeDefaultOptionIfSimple(request);

        // 3. INV-A~D 전량 in-memory 선검증(저장 전 완결). 통과 시 임시키→그룹 index 매핑을 반환한다.
        Map<String, Integer> keyToGroupIndex = validateAndIndexOptionKeys(effectiveRequest);
        // R5-3: display_order 계층별 중복 검증(그룹·값·변형·gap 허용·중복만 차단·저장 전 완결·INV-A~D 동일 경계).
        validateDisplayOrders(effectiveRequest);

        // 4. 저장 오케스트레이션(Product→OptionGroup/Value→Variant→Inventory).
        Product product = productRepository.save(Product.create(
                sellerId,
                effectiveRequest.categoryId(),
                effectiveRequest.name(),
                effectiveRequest.description(),
                effectiveRequest.basePrice(),
                effectiveRequest.thumbnailUrl()));

        Map<String, Long> keyToValueId = saveOptionGroupsAndValues(product, effectiveRequest.optionGroups());
        Map<Integer, Integer> groupIndexToSlot = buildGroupIndexToSlot(effectiveRequest.optionGroups());
        // INV-E: 요청 내 동일 옵션 조합 variant 중복 차단(uk NULL distinct 보강·저장 루프 진입 전 in-memory·mapOptionSlots 해소 결과 기준).
        validateVariantOptionCombinations(effectiveRequest.variants(), keyToGroupIndex, groupIndexToSlot, keyToValueId);
        List<ProductVariant> savedVariants = saveVariants(
                product.getId(), effectiveRequest.variants(), keyToGroupIndex, groupIndexToSlot, keyToValueId);

        // 5. 각 변형 초기 재고 시딩(INBOUND·referenceId=productId·initializeInventory 내부에서 History 동반 기록).
        List<ProductVariantRequest> variantRequests = effectiveRequest.variants();
        for (int i = 0; i < variantRequests.size(); i++) {
            inventoryService.initializeInventory(
                    savedVariants.get(i).getId(), product.getId(), variantRequests.get(i).initialStock());
        }

        log.info("[ProductRegistration] 등록 완료 sellerId={} productPublicId={} variantCount={}",
                sellerId, product.getPublicId(), savedVariants.size());
        return new ProductRegistrationResponse(
                product.getPublicId(),
                savedVariants.stream().map(ProductVariant::getPublicId).toList());
    }

    /**
     * 단순상품(옵션 미지정)이면 DEFAULT 옵션 1조를 합성한 등록 요청으로 정규화하고, 옵션 명시 요청은 그대로 통과시킨다(α′).
     * 단순상품 판정은 optionGroups가 빈 경우이며, 이때 variant는 정확히 1개이고 그 optionKeys도 빈 배열이어야 한다(위반 시 400).
     * 합성 요청은 option1_value_id NOT NULL을 충족하는 DEFAULT OptionGroup·OptionValue와 이를 참조하는 단일 variant로 구성되어,
     * 옵션 있는 경로와 동일한 검증·저장 파이프라인을 그대로 재사용한다(중복 분기 회피·기조 4). 옵션 명시 요청은 동일 객체를
     * 반환하므로 기존 흐름이 무변경으로 보존된다.
     *
     * @throws IllegalArgumentException optionGroups가 빈데 variant가 1개가 아니거나 variant의 optionKeys가 비어 있지 않을 때(400)
     */
    private ProductRegistrationRequest synthesizeDefaultOptionIfSimple(ProductRegistrationRequest request) {
        if (!request.optionGroups().isEmpty()) {
            return request; // 옵션 명시 경로 — 무변경(동일 객체 반환).
        }
        List<ProductVariantRequest> variants = request.variants();
        if (variants.size() != 1 || !variants.get(0).optionKeys().isEmpty()) {
            throw new IllegalArgumentException(
                    "옵션 미지정 단순상품은 variant 1개·optionKeys 빈 배열이어야 합니다(현재 variants=" + variants.size() + ").");
        }
        ProductVariantRequest source = variants.get(0);
        ProductOptionGroupRequest defaultGroup = new ProductOptionGroupRequest(
                DEFAULT_OPTION_GROUP_NAME,
                DEFAULT_DISPLAY_ORDER,
                List.of(new ProductOptionValueRequest(DEFAULT_OPTION_TEMP_KEY, DEFAULT_OPTION_VALUE, DEFAULT_DISPLAY_ORDER)));
        ProductVariantRequest defaultVariant = new ProductVariantRequest(
                source.variantCode(),
                source.sellerSku(),
                source.barcode(),
                source.additionalPrice(),
                source.displayOrder(),
                source.initialStock(),
                List.of(DEFAULT_OPTION_TEMP_KEY));
        return new ProductRegistrationRequest(
                request.categoryId(),
                request.name(),
                request.description(),
                request.basePrice(),
                request.thumbnailUrl(),
                List.of(defaultGroup),
                List.of(defaultVariant));
    }

    /**
     * 임시키→그룹 index 매핑을 구성하며 INV-D·임시키 유일성·변형별 INV-A~C를 in-memory로 선검증한다(저장 전 완결).
     * "같은 Product 소속" 정합은 요청 구조상 자동 보장된다(전역 카탈로그가 아니라 단일 요청 본문·INV-C 주석).
     *
     * @return 임시키(String) → 그룹 index(Integer)
     * @throws IllegalArgumentException INV-D·임시키 유일성·INV-A~C 위반 시(400)
     */
    private Map<String, Integer> validateAndIndexOptionKeys(ProductRegistrationRequest request) {
        List<ProductOptionGroupRequest> groups = request.optionGroups();
        // INV-D: OptionGroup 개수 ≤ 3.
        if (groups.size() > MAX_OPTION_GROUPS) {
            throw new IllegalArgumentException(
                    "옵션 그룹은 최대 " + MAX_OPTION_GROUPS + "개까지 허용됩니다(INV-D). 현재=" + groups.size());
        }

        // 임시키→그룹 index. 임시키는 요청 본문 내에서 유일해야 한다(중복 정의 금지).
        Map<String, Integer> keyToGroupIndex = new HashMap<>();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            for (ProductOptionValueRequest value : groups.get(groupIndex).values()) {
                if (keyToGroupIndex.putIfAbsent(value.key(), groupIndex) != null) {
                    throw new IllegalArgumentException(
                            "옵션값 임시키가 요청 내에서 중복 정의되었습니다: key=" + value.key());
                }
            }
        }

        // 변형별 INV-B(arity)·INV-C(존재)·INV-A(그룹당 1값)·중복 참조 금지.
        List<ProductVariantRequest> variants = request.variants();
        for (int i = 0; i < variants.size(); i++) {
            validateVariantOptionKeys(i, variants.get(i).optionKeys(), keyToGroupIndex, groups.size());
        }
        return keyToGroupIndex;
    }

    /**
     * 한 변형의 optionKeys를 검증한다. INV-B(그룹 수 = optionKeys 수)와 INV-A(그룹당 1값·중복 그룹 금지)가 함께 성립하면
     * 전 OptionGroup을 정확히 1회씩 참조함이 보장된다(pigeonhole·INV-B 완전 커버리지).
     *
     * @throws IllegalArgumentException INV-B·INV-C·INV-A·중복 참조 위반 시(400)
     */
    private void validateVariantOptionKeys(
            int variantIndex, List<String> optionKeys, Map<String, Integer> keyToGroupIndex, int groupCount) {
        // INV-B: arity 일치(그룹 수 = optionKeys 수).
        if (optionKeys.size() != groupCount) {
            throw new IllegalArgumentException("변형[" + variantIndex + "]의 optionKeys 수(" + optionKeys.size()
                    + ")가 옵션 그룹 수(" + groupCount + ")와 일치하지 않습니다(INV-B).");
        }
        Set<Integer> referencedGroups = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (String key : optionKeys) {
            if (!seenKeys.add(key)) {
                throw new IllegalArgumentException(
                        "변형[" + variantIndex + "]이 동일 임시키를 중복 참조합니다: key=" + key);
            }
            Integer groupIndex = keyToGroupIndex.get(key);
            if (groupIndex == null) {
                throw new IllegalArgumentException(
                        "변형[" + variantIndex + "]이 존재하지 않는 임시키를 참조합니다(INV-C): key=" + key);
            }
            if (!referencedGroups.add(groupIndex)) {
                throw new IllegalArgumentException(
                        "변형[" + variantIndex + "]이 동일 옵션 그룹에서 2개 이상 값을 참조합니다(INV-A): key=" + key);
            }
        }
    }

    /**
     * R5-3: display_order 중복을 계층별로 차단한다 — OptionGroup 간·각 그룹 내 OptionValue 간·Variant 간. gap(예: 10·20·30)은
     * 허용하며 연속성은 강제하지 않는다(중복만 차단). 저장 전 in-memory 완결(INV-A~D와 동일 경계).
     *
     * @throws IllegalArgumentException 어느 한 계층에서 displayOrder가 중복될 때(400·중복 계층·displayOrder 값 메시지 명시)
     */
    private void validateDisplayOrders(ProductRegistrationRequest request) {
        // 1. OptionGroup 간 displayOrder 중복 금지 + 2. 각 그룹 내 OptionValue 간 displayOrder 중복 금지(그룹 단위 리셋).
        Set<Integer> groupOrders = new HashSet<>();
        for (ProductOptionGroupRequest group : request.optionGroups()) {
            if (!groupOrders.add(group.displayOrder())) {
                throw new IllegalArgumentException(
                        "옵션 그룹 간 displayOrder가 중복됩니다(R5-3): displayOrder=" + group.displayOrder());
            }
            Set<Integer> valueOrders = new HashSet<>();
            for (ProductOptionValueRequest value : group.values()) {
                if (!valueOrders.add(value.displayOrder())) {
                    throw new IllegalArgumentException("옵션 그룹 '" + group.name()
                            + "' 내 옵션값 간 displayOrder가 중복됩니다(R5-3): displayOrder=" + value.displayOrder());
                }
            }
        }
        // 3. Variant 간 displayOrder 중복 금지.
        Set<Integer> variantOrders = new HashSet<>();
        for (ProductVariantRequest variant : request.variants()) {
            if (!variantOrders.add(variant.displayOrder())) {
                throw new IllegalArgumentException(
                        "상품 변형 간 displayOrder가 중복됩니다(R5-3): displayOrder=" + variant.displayOrder());
            }
        }
    }

    /**
     * OptionGroup·OptionValue를 저장하며 임시키→저장된 OptionValue id Map을 구성한다. Product Aggregate 내부 엔티티는
     * 저장된 상위(Product·OptionGroup)를 팩토리에 전달해 FK를 배선한다(엔티티 연관 팩토리 정합).
     *
     * @return 임시키(String) → 저장된 OptionValue id(Long)
     */
    private Map<String, Long> saveOptionGroupsAndValues(Product product, List<ProductOptionGroupRequest> groups) {
        Map<String, Long> keyToValueId = new HashMap<>();
        for (ProductOptionGroupRequest groupRequest : groups) {
            ProductOptionGroup group = productOptionGroupRepository.save(
                    ProductOptionGroup.create(product, groupRequest.name(), groupRequest.displayOrder()));
            for (ProductOptionValueRequest valueRequest : groupRequest.values()) {
                ProductOptionValue value = productOptionValueRepository.save(
                        ProductOptionValue.create(group, valueRequest.value(), valueRequest.displayOrder()));
                keyToValueId.put(valueRequest.key(), value.getId());
            }
        }
        return keyToValueId;
    }

    /**
     * OptionGroup을 displayOrder 오름차순으로 정렬해 그룹 index→옵션 슬롯(0=option1·1=option2·2=option3)을 매핑한다.
     * displayOrder 동순위는 요청 정의 순서를 유지한다({@link java.util.stream.Stream#sorted} stable 정렬).
     *
     * @return 그룹 index(Integer) → 슬롯(Integer)
     */
    private Map<Integer, Integer> buildGroupIndexToSlot(List<ProductOptionGroupRequest> groups) {
        List<Integer> groupIndicesByDisplayOrder = IntStream.range(0, groups.size()).boxed()
                .sorted(Comparator.comparingInt(groupIndex -> groups.get(groupIndex).displayOrder()))
                .toList();
        Map<Integer, Integer> groupIndexToSlot = new HashMap<>();
        for (int slot = 0; slot < groupIndicesByDisplayOrder.size(); slot++) {
            groupIndexToSlot.put(groupIndicesByDisplayOrder.get(slot), slot);
        }
        return groupIndexToSlot;
    }

    /**
     * INV-E: 요청 내 variant들의 옵션 조합(해소된 option1~3 valueId 튜플)이 서로 중복되지 않는지 in-memory로 검증한다.
     * uk_product_variant_options는 MariaDB에서 NULL을 distinct로 취급하므로 option2·option3가 NULL인 1~2그룹 상품의 동일
     * 조합을 DB 제약만으로는 막지 못한다. 저장 루프 진입 전 앱 레벨에서 차단하며, uk catch→409({@link #saveVariants})는 3그룹
     * 보조 방어선으로 유지한다. 조합 비교는 {@link #mapOptionSlots} 해소 결과(valueId 튜플·id 기준)로 하며 옵션값 이름은
     * 비교하지 않는다(이름 유일성은 범위 밖). 단순상품은 variant가 1개이므로 자연히 통과한다.
     *
     * @throws IllegalArgumentException 동일 옵션 조합의 variant가 요청 내에서 중복될 때(400)
     */
    private void validateVariantOptionCombinations(
            List<ProductVariantRequest> variants,
            Map<String, Integer> keyToGroupIndex,
            Map<Integer, Integer> groupIndexToSlot,
            Map<String, Long> keyToValueId) {
        Set<List<Long>> seenCombinations = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            Long[] optionSlots = mapOptionSlots(
                    variants.get(i).optionKeys(), keyToGroupIndex, groupIndexToSlot, keyToValueId);
            List<Long> combination = Arrays.asList(optionSlots[0], optionSlots[1], optionSlots[2]);
            if (!seenCombinations.add(combination)) {
                throw new IllegalArgumentException("동일 옵션 조합의 변형이 요청 내에서 중복됩니다(INV-E): variant["
                        + i + "], 조합(option1~3 valueId)=" + combination);
            }
        }
    }

    /**
     * 변형을 저장 순서대로 생성·저장하고 반환한다. optionKeys는 그룹 displayOrder 슬롯에 따라 option1~3_value_id로 해소한다.
     * 저장 후 flush로 uk_product_variant_options(product_id·option1~3_value_id) 제약을 트랜잭션 내에서 확정하며, 위반 시
     * 409로 변환한다(사전 조회 없이 DB 제약을 최종 방어선으로 삼음·던진 예외가 @Transactional 경계를 넘어 전체 롤백).
     *
     * @throws ProductVariantOptionConflictException 동일 옵션 조합 변형 중복 시(409)
     */
    private List<ProductVariant> saveVariants(
            Long productId,
            List<ProductVariantRequest> variants,
            Map<String, Integer> keyToGroupIndex,
            Map<Integer, Integer> groupIndexToSlot,
            Map<String, Long> keyToValueId) {
        List<ProductVariant> savedVariants = new ArrayList<>();
        try {
            for (ProductVariantRequest variantRequest : variants) {
                Long[] optionSlots = mapOptionSlots(
                        variantRequest.optionKeys(), keyToGroupIndex, groupIndexToSlot, keyToValueId);
                ProductVariant variant = ProductVariant.create(
                        productId,
                        variantRequest.variantCode(),
                        variantRequest.sellerSku(),
                        variantRequest.barcode(),
                        variantRequest.additionalPrice(),
                        variantRequest.displayOrder(),
                        optionSlots[0],
                        optionSlots[1],
                        optionSlots[2]);
                savedVariants.add(productVariantRepository.save(variant));
            }
            // IDENTITY는 save 시 즉시 INSERT되나, 명시적 flush로 uk_product_variant_options 제약 위반을 트랜잭션 내에서 표면화한다.
            productVariantRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            log.warn("[ProductRegistration] 변형 옵션 조합 중복 차단(409·uk_product_variant_options) productId={}: {}",
                    productId, exception.getMostSpecificCause().getMessage());
            throw new ProductVariantOptionConflictException(
                    "동일 옵션 조합의 상품 변형이 이미 존재합니다(uk_product_variant_options).");
        }
        return savedVariants;
    }

    /**
     * 변형의 optionKeys를 그룹 displayOrder 슬롯에 따라 option1~3 valueId 배열로 해소한다. 미참조 슬롯은 null이다
     * (option2·option3 nullable). INV-B로 첫 슬롯(option1)은 항상 채워짐이 보장된다.
     *
     * @return 길이 {@link #MAX_OPTION_GROUPS} 배열(index 0=option1·1=option2·2=option3)
     */
    private Long[] mapOptionSlots(
            List<String> optionKeys,
            Map<String, Integer> keyToGroupIndex,
            Map<Integer, Integer> groupIndexToSlot,
            Map<String, Long> keyToValueId) {
        Long[] optionSlots = new Long[MAX_OPTION_GROUPS];
        for (String key : optionKeys) {
            int slot = groupIndexToSlot.get(keyToGroupIndex.get(key));
            optionSlots[slot] = keyToValueId.get(key);
        }
        return optionSlots;
    }
}
