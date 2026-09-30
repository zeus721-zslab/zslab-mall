package com.zslab.mall.productquestion.service;

import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import org.springframework.stereotype.Component;

/**
 * 질문 공개 조회·즉시 답·질문 등록이 공유하는 상품 노출 판정(Track 106-2). 리뷰({@code ReviewQueryService.requireProduct})·상품 상세와 같은
 * 기준이다 — 판매중·판매중지 상품이고 판매자가 ACTIVE일 때만 통과하고, 그 밖은 404로 은닉한다(질문 경로로 비노출 상품의 존재가 드러나지 않게).
 * 리뷰 코드를 건드리지 않기 위해 판정만 옮겨 둔다. 트랜잭션은 호출하는 서비스의 것을 쓴다.
 */
@Component
public class ExposedProductReader {

    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;

    public ExposedProductReader(ProductRepository productRepository, SellerRepository sellerRepository) {
        this.productRepository = productRepository;
        this.sellerRepository = sellerRepository;
    }

    /** @throws ProductNotFoundException 상품 미존재·삭제·비노출·판매자 비-ACTIVE(404) */
    public Product require(String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .filter(found -> found.getStatus() == ProductStatus.SALE || found.getStatus() == ProductStatus.STOPPED)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        sellerRepository.findById(product.getSellerId())
                .filter(seller -> seller.getStatus() == SellerStatus.ACTIVE)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        return product;
    }
}
