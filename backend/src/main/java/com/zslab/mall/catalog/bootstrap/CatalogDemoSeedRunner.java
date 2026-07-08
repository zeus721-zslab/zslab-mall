package com.zslab.mall.catalog.bootstrap;

import com.zslab.mall.category.entity.Category;
import com.zslab.mall.category.repository.CategoryRepository;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductOptionGroup;
import com.zslab.mall.product.entity.ProductOptionValue;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.repository.ProductOptionGroupRepository;
import com.zslab.mall.product.repository.ProductOptionValueRepository;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈 카탈로그 노출 검증용 데모 상품 부트 시드(FE-04). FE-03에서 공통 레이아웃·홈·ProductCard·SSR 직결은 완성했으나 카탈로그가
 * 비어(items 0) 상품 카드가 렌더되지 않았다. 본 Runner는 "노출 성립 최소 체인"의 데모 상품 2건을 dev 부팅 시 멱등 공급해
 * 홈 카드·반응형·hover의 실물 렌더 검증을 가능하게 한다.
 *
 * <p><b>활성·멱등·전례</b>: {@code catalog.demo-seed.enabled=true}일 때만 빈이 생성된다({@link ConditionalOnProperty}·
 * 프로퍼티 부재 시 기본 OFF·dev 한정 활성은 아래 '테스트·운영 격리' 참조). 활성 시 로직은
 * {@link com.zslab.mall.auth.bootstrap.SuperAdminBootstrapRunner}와 동형이다({@code CommandLineRunner} + 존재 확인 후
 * 없을 때만 생성 + {@code @Transactional} + 도메인 팩토리 경유). 멱등 키는 "카탈로그 상품 존재 여부"
 * ({@code productRepository.count() == 0})이므로 재기동에 안전하다(이미 상품이 있으면 skip). raw SQL·JDBC 직삽입 없이 각
 * 도메인 팩토리를 경유해 public_id({@code @PrePersist})·불변식·상태 고정을 자동 준수한다.
 *
 * <p><b>노출 성립 체인</b>(recon-report-66 A-1 주2): 카탈로그 목록은 {@code Product.status=SALE ∧ Seller.status=ACTIVE}이며
 * 품절이 아니려면 {@code Variant.status=SALE ∧ Inventory.available>0}이어야 한다. 따라서 Seller(ACTIVE)→Category(루트)→
 * Product({@link Product#approve()}로 PENDING→SALE 전이)→OptionGroup/OptionValue(DEFAULT sentinel·variant의
 * {@code option1_value_id NOT NULL} 충족용)→Variant(SALE)→Inventory(재고 &gt; 0) 순으로 생성한다. 홈 카드 썸네일은
 * ProductImage가 아니라 {@code product.thumbnail_url} 한 컬럼에서 온다(recon A-1 주1).
 *
 * <p><b>단순상품 DEFAULT sentinel</b>: {@code ProductVariant.option1_value_id}가 NOT NULL이라 variant 앞에 옵션값 1개가
 * 선행돼야 한다. 옵션이 없는 단순상품은 그룹명 {@code "DEFAULT"}로 1조를 합성하며, 이는 카탈로그 응답에서 숨겨진다
 * ({@code ProductRegistrationService}·{@code ProductCatalogService}의 {@code DEFAULT_OPTION_GROUP_NAME}과 동일 계약).
 *
 * <p><b>테스트·운영 격리</b>: {@code CommandLineRunner}는 {@code @SpringBootTest} 컨텍스트 기동 시에도 실행되므로,
 * 무조건 활성화하면 싱글톤 공유 테스트 컨테이너에 데모 행을 커밋해 전역 상태를 단언하는 테스트를 깨뜨린다
 * ({@code inventory.variant_id} UNIQUE 충돌·{@code product_variant} 전역 count 위반). 이를 {@code catalog.demo-seed.enabled}
 * 플래그로 차단한다 — dev(local 프로파일·application-local.yml true)에서만 켜지고, 테스트는 gradle test 태스크가 false로
 * 명시 차단, 운영은 prod 프로파일에서 미설정이라 빈이 생성되지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CatalogDemoSeedRunner implements CommandLineRunner {

    // 단순상품 합성 sentinel(ProductRegistrationService의 DEFAULT_OPTION_GROUP_NAME/VALUE와 동일 계약·카탈로그에서 숨김).
    private static final String DEFAULT_OPTION_GROUP_NAME = "DEFAULT";
    private static final String DEFAULT_OPTION_VALUE = "DEFAULT";
    private static final int DEFAULT_DISPLAY_ORDER = 0;
    // variant 추가금 0 → 대표가(displayPrice) = basePrice + MIN(additional) = basePrice(깔끔한 표시가).
    private static final long DEMO_VARIANT_ADDITIONAL_PRICE = 0L;
    private static final int DEMO_INITIAL_STOCK = 100;

    private final SellerRepository sellerRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductVariantRepository productVariantRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * 카탈로그가 비어 있을 때에 한해 데모 Seller·Category·상품 2건(+옵션·변형·재고)을 원자 생성한다. 이미 상품이 있으면
     * 무작업(skip)해 재기동·기존 데이터에 안전하다. 부분 생성(Seller만 저장되고 Product 실패)은 {@code @Transactional}로 방지한다.
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("카탈로그에 상품이 이미 존재하여 데모 시드를 건너뜁니다.");
            return;
        }

        Seller seller = sellerRepository.save(
                Seller.create("데모 상점", null, "데모 대표", null, null, SellerStatus.ACTIVE));
        Category category = categoryRepository.save(
                Category.create(null, "데모", 0, DEFAULT_DISPLAY_ORDER));

        seedProduct(seller.getId(), category.getId(),
                "데모 코튼 티셔츠", "홈 노출 검증용 데모 상품입니다.", 19900L,
                "https://picsum.photos/seed/zslab-mall-1/600/600", "DEMO-TSHIRT");
        seedProduct(seller.getId(), category.getId(),
                "데모 베이직 후디", "홈 노출 검증용 데모 상품입니다.", 39900L,
                "https://picsum.photos/seed/zslab-mall-2/600/600", "DEMO-HOODIE");

        log.info("데모 카탈로그 시드 완료: Seller 1·Category 1·Product 2(각 DEFAULT 변형·재고 {}).", DEMO_INITIAL_STOCK);
    }

    /**
     * 노출 성립 최소 체인 1건을 생성한다: Product(승인으로 SALE 전이)→DEFAULT 옵션 그룹/값→Variant(SALE)→Inventory(재고).
     * OptionGroup/OptionValue는 저장된 상위(Product·OptionGroup)를 팩토리에 전달해 FK를 배선한다
     * ({@code ProductRegistrationService.saveOptionGroupsAndValues} 정합).
     */
    private void seedProduct(Long sellerId, Long categoryId, String name, String description, long basePrice,
            String thumbnailUrl, String variantCode) {
        Product product = Product.create(sellerId, categoryId, name, description, basePrice, thumbnailUrl);
        product.approve(); // PENDING → SALE (카탈로그 노출 전이)
        Product savedProduct = productRepository.save(product);

        ProductOptionGroup optionGroup = productOptionGroupRepository.save(
                ProductOptionGroup.create(savedProduct, DEFAULT_OPTION_GROUP_NAME, DEFAULT_DISPLAY_ORDER));
        ProductOptionValue optionValue = productOptionValueRepository.save(
                ProductOptionValue.create(optionGroup, DEFAULT_OPTION_VALUE, DEFAULT_DISPLAY_ORDER));

        ProductVariant variant = productVariantRepository.save(ProductVariant.create(
                savedProduct.getId(), variantCode, null, null,
                DEMO_VARIANT_ADDITIONAL_PRICE, DEFAULT_DISPLAY_ORDER,
                optionValue.getId(), null, null));

        inventoryRepository.save(Inventory.create(variant.getId(), DEMO_INITIAL_STOCK));
    }
}
