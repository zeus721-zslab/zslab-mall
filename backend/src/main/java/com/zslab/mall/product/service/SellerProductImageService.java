package com.zslab.mall.product.service;

import com.zslab.mall.product.controller.request.AddProductImageRequest;
import com.zslab.mall.product.controller.request.ReorderProductImagesRequest;
import com.zslab.mall.product.controller.response.ProductImageResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductImage;
import com.zslab.mall.product.exception.ProductImageNotFoundException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductImageRepository;
import com.zslab.mall.product.repository.ProductRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 상품 이미지 관리 Application Service(Track 59 BL-6). 본인 소유 상품의 이미지 등록·대표지정·정렬변경·삭제(soft)를
 * 담당한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>소유권은 seller→product→image 2-hop 스코프 조회로 강제한다 — 상품 진입은 {@code findByIdAndSellerId},
 * 이미지 단건은 {@code findByIdAndProduct_IdAndProduct_SellerId}로 스코프해 타 판매자 소유·미존재를 404로 은닉한다
 * (BOLA 차단·{@link UserAddressService} 선례). 대표 이미지(is_main) 단일성은 DB 제약이 아니라 본 서비스의
 * {@link #applyMain} demote-then-set 로직이 동일 트랜잭션 내에서 보장한다({@code UserAddressService.setDefault} 이식).
 *
 * <p>대표 이미지와 {@code product.thumbnail_url}은 현재 독립이다(동기화하지 않음·Track 59 결정3). 동기화가 필요해지면
 * {@link #applyMain} 내부 SEAM 지점 한 곳만 손대면 되도록 대표 지정 로직을 단일 private 메서드로 격리했다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SellerProductImageService {

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;

    /**
     * 이미지를 상품 끝에 등록한다(Track 59 BL-6). displayOrder는 현재 활성 이미지 수로 append하며, {@code main=true}면
     * 등록 직후 대표로 지정하고 기존 대표를 강등한다.
     *
     * @throws ProductNotFoundException 상품이 없거나 요청 판매자 소유가 아닌 경우(404)
     */
    public ProductImageResponse add(Long sellerId, Long productId, AddProductImageRequest request) {
        Product product = requireOwnedProduct(sellerId, productId);
        int displayOrder = (int) productImageRepository.countByProductId(productId);
        ProductImage image = productImageRepository.save(
                ProductImage.create(product, request.imageUrl(), displayOrder, false));
        if (request.main()) {
            applyMain(image, productId);
        }

        log.info("[ProductImage] 이미지 등록 sellerId={} productId={} imageId={} displayOrder={} main={}",
                sellerId, productId, image.getId(), displayOrder, request.main());
        return toResponse(image);
    }

    /**
     * 대표 이미지를 지정한다(Track 59 BL-6). 기존 대표를 강등한 뒤 대상을 승격한다(demote-then-set·동일 TX·단일성 보장).
     * 대상이 이미 대표이면 강등·승격이 상쇄되어 멱등이다.
     *
     * @throws ProductImageNotFoundException 이미지가 없거나 요청 판매자 소유가 아닌 경우(404)
     */
    public void designateMain(Long sellerId, Long productId, Long imageId) {
        ProductImage target = requireOwnedImage(sellerId, productId, imageId);
        applyMain(target, productId);

        log.info("[ProductImage] 대표 이미지 지정 sellerId={} productId={} imageId={}", sellerId, productId, imageId);
    }

    /**
     * 이미지 정렬 순서를 재배치한다(Track 59 BL-6). imageIds는 대상 상품의 활성 이미지 전량을 순서대로 정확히 열거해야 하며
     * (누락·과잉·중복 시 400), 목록 순서대로 display_order 0..n-1을 부여한다.
     *
     * @throws ProductNotFoundException 상품이 없거나 요청 판매자 소유가 아닌 경우(404)
     * @throws IllegalArgumentException imageIds가 상품 활성 이미지 집합과 일치하지 않는 경우(400)
     */
    public void reorder(Long sellerId, Long productId, ReorderProductImagesRequest request) {
        requireOwnedProduct(sellerId, productId);
        List<Long> requestedIds = request.imageIds();
        Map<Long, ProductImage> imagesById = productImageRepository.findByProductId(productId).stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));

        // 요청 imageIds가 활성 이미지 집합과 정확히 일치해야 한다. 크기 비교로 중복 참조를, 집합 동일성으로 누락·과잉을 차단한다.
        Set<Long> requestedSet = new HashSet<>(requestedIds);
        if (requestedIds.size() != requestedSet.size() || !imagesById.keySet().equals(requestedSet)) {
            throw new IllegalArgumentException(
                    "이미지 정렬 요청이 상품 활성 이미지 집합과 일치하지 않습니다(중복·누락·과잉 불가): productId=" + productId);
        }

        for (int order = 0; order < requestedIds.size(); order++) {
            ProductImage image = imagesById.get(requestedIds.get(order));
            image.changeDisplayOrder(order);
            productImageRepository.save(image);
        }

        log.info("[ProductImage] 이미지 정렬 변경 sellerId={} productId={} count={}", sellerId, productId, requestedIds.size());
    }

    /**
     * 이미지를 삭제한다(Track 59 BL-6·soft delete). 대표 이미지 삭제 시 다른 이미지 자동 승격은 하지 않는다(재지정은 판매자
     * 몫·UserAddress delete 선례). thumbnail_url은 독립이라 영향받지 않는다.
     *
     * @throws ProductImageNotFoundException 이미지가 없거나 요청 판매자 소유가 아닌 경우(404)
     */
    public void delete(Long sellerId, Long productId, Long imageId) {
        ProductImage image = requireOwnedImage(sellerId, productId, imageId);
        image.markDeleted();
        productImageRepository.save(image);

        log.info("[ProductImage] 이미지 삭제(soft) sellerId={} productId={} imageId={}", sellerId, productId, imageId);
    }

    /**
     * 대표 이미지를 target으로 확정한다(demote-then-set·단일성 보장 단일 지점). 기존 대표가 target과 다르면 강등한 뒤
     * target을 승격한다. 대상이 이미 대표이면 강등 대상에서 제외되어 멱등이다.
     */
    private void applyMain(ProductImage target, Long productId) {
        productImageRepository.findByProductIdAndMainTrue(productId)
                .filter(current -> !current.getId().equals(target.getId()))
                .ifPresent(current -> {
                    current.unmarkMain();
                    productImageRepository.save(current);
                });
        // SEAM: 향후 thumbnail_url 동기화 지점(Track 59 결정3-β). 대표 확정과 product.thumbnail_url 반영을 묶으려면 여기 1줄.
        target.markMain();
        productImageRepository.save(target);
    }

    /**
     * @throws ProductImageNotFoundException 이미지가 없거나 요청 판매자 소유가 아닌 경우(404·2-hop 소유권 스코프)
     */
    private ProductImage requireOwnedImage(Long sellerId, Long productId, Long imageId) {
        return productImageRepository.findByIdAndProduct_IdAndProduct_SellerId(imageId, productId, sellerId)
                .orElseThrow(() -> new ProductImageNotFoundException("상품 이미지를 찾을 수 없습니다: imageId=" + imageId));
    }

    /**
     * @throws ProductNotFoundException 상품이 없거나 요청 판매자 소유가 아닌 경우(404·소유권 스코프)
     */
    private Product requireOwnedProduct(Long sellerId, Long productId) {
        return productRepository.findByIdAndSellerId(productId, sellerId)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: productId=" + productId));
    }

    private static ProductImageResponse toResponse(ProductImage image) {
        return new ProductImageResponse(
                image.getId(), image.getImageUrl(), image.getDisplayOrder(), image.isMain());
    }
}
