package com.zslab.mall.product.service;

import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductInvalidStateException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 승인 전이 Application Service(Track 50·운영자 주도). 등록 시 PENDING인 상품을 승인(→SALE) 또는 거부(→REJECTED)한다.
 * 권한은 SecurityConfig {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로(운영자 전용) 서비스는 소유권 대조 없이
 * 전이 로직만 갖는다.
 *
 * <p><b>동시성(비관적 락)</b>: 전이 대상을 {@link ProductRepository#findByPublicIdForUpdate}(SELECT ... FOR UPDATE)로 조회해
 * 행 단위로 직렬화한다(SettlementTransitionService·D-101 house pattern 준용). 승인 진입키가 public_id이므로 단일 락 쿼리를
 * public_id 기준으로 조회한다.
 *
 * <p><b>전이 위반</b>: 대상이 PENDING이 아니면 Aggregate mutator({@link Product#approve}·{@link Product#reject})가
 * {@link IllegalStateException}을 던지며, 이를 {@link ProductInvalidStateException}(422)으로 흡수한다 — 직접
 * IllegalStateException 매핑은 500 fallback으로 새므로 금지한다. 전이 성공 시 status 변경은 managed 엔티티 dirty checking으로
 * 커밋된다(별도 save 불요·Settlement 정합).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProductApprovalService {

    private final ProductRepository productRepository;

    /**
     * 상품을 승인한다(PENDING → SALE).
     *
     * @param publicId 승인 대상 상품 public_id(prd_)
     * @return 승인된 Product
     * @throws ProductNotFoundException     상품 미존재(404)
     * @throws ProductInvalidStateException SALE 전이가 불가한 상태(PENDING 아님)인 경우(422)
     */
    public Product approve(String publicId) {
        Product product = productRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: publicId=" + publicId));

        if (product.getStatus() == ProductStatus.SALE) {
            log.info("[Product] 이미 SALE → 승인 건너뜀: publicId={}", publicId);
            return product;
        }

        try {
            product.approve();
        } catch (IllegalStateException exception) {
            throw new ProductInvalidStateException("승인할 수 없는 상품 상태입니다: " + exception.getMessage());
        }
        log.info("[Product] 승인 전이 완료(→SALE): publicId={}", publicId);
        return product;
    }

    /**
     * 상품을 거부한다(PENDING → REJECTED·종료 상태).
     *
     * @param publicId 거부 대상 상품 public_id(prd_)
     * @return 거부된 Product
     * @throws ProductNotFoundException     상품 미존재(404)
     * @throws ProductInvalidStateException REJECTED 전이가 불가한 상태(PENDING 아님)인 경우(422)
     */
    public Product reject(String publicId) {
        Product product = productRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: publicId=" + publicId));

        if (product.getStatus() == ProductStatus.REJECTED) {
            log.info("[Product] 이미 REJECTED → 거부 건너뜀: publicId={}", publicId);
            return product;
        }

        try {
            product.reject();
        } catch (IllegalStateException exception) {
            throw new ProductInvalidStateException("거부할 수 없는 상품 상태입니다: " + exception.getMessage());
        }
        log.info("[Product] 거부 전이 완료(→REJECTED): publicId={}", publicId);
        return product;
    }
}
