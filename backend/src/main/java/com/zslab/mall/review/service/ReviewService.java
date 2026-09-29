package com.zslab.mall.review.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.entity.ReviewKeyword;
import com.zslab.mall.review.event.ReviewChangedEvent;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.exception.ReviewAlreadyExistsException;
import com.zslab.mall.review.exception.ReviewInvalidStateException;
import com.zslab.mall.review.exception.ReviewNotEligibleException;
import com.zslab.mall.review.exception.ReviewNotFoundException;
import com.zslab.mall.review.repository.ReviewKeywordRepository;
import com.zslab.mall.review.repository.ReviewRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 리뷰 쓰기(Track 106-1): 작성·수정·삭제.
 *
 * <p><b>자격</b>: 본인 주문의 구매확정(CONFIRMED) 품목만, 품목당 1개(삭제한 리뷰 포함 — 재작성 불가). 미존재·타인 품목은 구매확정과 같은
 * {@link OrderNotFoundException}(404)으로 존재를 은닉한다. CONFIRMED는 나가는 전이가 없는 종결 상태라 확정 뒤 자격이 사라지는 경로가 없다.
 * 동시 작성은 사전 확인을 함께 통과해도 uk_review_order_item이 한 건만 남긴다(나머지 409).
 */
@Slf4j
@Service
@Transactional
public class ReviewService {

    /** 품목당 1개 UNIQUE(V40). 이 제약 위반만 동시 작성 충돌(409)로 바꾼다. */
    static final String ORDER_ITEM_UNIQUE_KEY = "uk_review_order_item";

    private final ReviewRepository reviewRepository;
    private final ReviewKeywordRepository reviewKeywordRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ReviewAttachmentService reviewAttachmentService;
    private final TracedEventPublisher eventPublisher;

    public ReviewService(ReviewRepository reviewRepository, ReviewKeywordRepository reviewKeywordRepository,
            OrderItemRepository orderItemRepository, OrderRepository orderRepository, ProductRepository productRepository,
            ReviewAttachmentService reviewAttachmentService, TracedEventPublisher eventPublisher) {
        this.reviewRepository = reviewRepository;
        this.reviewKeywordRepository = reviewKeywordRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.reviewAttachmentService = reviewAttachmentService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 리뷰를 작성한다. option_label은 작성 시점 품목 스냅샷을 복사한다. product_id는 order_item.product_id에서만 채운다(요청값 불가 ·
     * 수정으로 바뀌지 않음).
     *
     * @throws OrderNotFoundException 품목 미존재·타인 주문(404)
     * @throws ReviewNotEligibleException 구매확정 전 품목·판매 종료(삭제)된 상품(422)
     * @throws ReviewAlreadyExistsException 품목에 리뷰가 이미 있음(삭제 포함·동시 작성 포함·409)
     * @throws MalformedRequestException 키워드 중복·상품에 쓸 수 없는 키워드·사진 소유/유형/연결/장수 위반(400)
     */
    public Review create(Long buyerId, String orderItemPublicId, int rating, List<String> keywordCodes, String content,
            List<String> attachmentIds) {
        OrderItem item = requireOwnedItem(buyerId, orderItemPublicId);
        if (item.getItemStatus() != OrderItemStatus.CONFIRMED) {
            throw new ReviewNotEligibleException("구매확정한 상품만 리뷰를 쓸 수 있습니다: " + orderItemPublicId);
        }
        if (reviewRepository.countIncludingDeletedByOrderItemId(item.getId()) > 0) {
            throw new ReviewAlreadyExistsException("이미 리뷰를 작성한 상품입니다: " + orderItemPublicId);
        }
        Product product = productRepository.findById(item.getProductId())
                .orElseThrow(() -> new ReviewNotEligibleException("판매가 종료된 상품은 리뷰를 쓸 수 없습니다: " + orderItemPublicId));
        List<Long> keywordIds = resolveKeywordIds(keywordCodes, product.getCategoryId());
        List<Attachment> photos = reviewAttachmentService.resolveForLink(buyerId, attachmentIds, null);

        Review review = Review.create(item.getId(), product.getId(), buyerId, rating, content, item.getOptionLabel(), keywordIds);
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException violation) {
            // 사전 확인 뒤 같은 품목 리뷰가 먼저 커밋된 경합만 409다. 그 밖 위반(길이·CHECK·FK)은 409로 가리지 않고 그대로 던진다.
            if (!isOrderItemDuplicate(violation)) {
                throw violation;
            }
            log.info("[Review] 동시 작성 충돌 orderItemPublicId={}", orderItemPublicId, violation);
            throw new ReviewAlreadyExistsException("이미 리뷰를 작성한 상품입니다: " + orderItemPublicId);
        }
        reviewAttachmentService.replaceLinks(photos, review.getId(), buyerId);
        eventPublisher.publishEvent(new ReviewChangedEvent(product.getId()));
        // 적립금(이월·구현 시 검토 A): 이 지점(작성 완료)에서 적립 "대기" 원장 행을 만든다. 텍스트/포토 구분은 연결 사진 수(photos.size() > 0)이고
        // 금액은 설정값 2개(텍스트·포토)로 둔다. 원장 테이블에 (출처 = 리뷰, 사유) 유일 제약을 걸어 중복 적립을 막고, 행 생성은 커밋 후에 한다.
        // 관리자 승인으로 대기 → 적립 확정, 숨김·삭제 시 차감 행을 추가한다(원행 수정 금지). 결제 시 사용은 별도 유스케이스다.
        log.info("[Review] 작성 reviewPublicId={} productId={} buyerId={}", review.getPublicId(), product.getId(), buyerId);
        return review;
    }

    /**
     * 작성자 수정(전체 교체). 숨김 리뷰는 고칠 수 없다 — 고치면 숨김 근거가 된 원문이 사라진다(감사 이력은 본문을 싣지 않는다). 삭제는
     * 숨김과 무관하게 허용한다(soft delete라 원문 행이 남는다).
     *
     * @throws ReviewNotFoundException 미존재·삭제·타인 리뷰(404)
     * @throws ReviewInvalidStateException 숨김 리뷰(422)
     * @throws MalformedRequestException 키워드·사진 검증 위반(400)
     */
    public void update(Long buyerId, String reviewPublicId, int rating, List<String> keywordCodes, String content,
            List<String> attachmentIds) {
        Review review = requireOwnReviewForUpdate(buyerId, reviewPublicId);
        if (review.getStatus() == ReviewStatus.HIDDEN) {
            throw new ReviewInvalidStateException("숨김 처리된 리뷰는 수정할 수 없습니다: " + reviewPublicId);
        }
        // 상품이 삭제됐으면 카테고리 세트는 쓸 수 없고 기본 세트만 허용한다.
        Long topCategoryId = productRepository.findById(review.getProductId()).map(Product::getCategoryId).orElse(null);
        List<Long> keywordIds = resolveKeywordIds(keywordCodes, topCategoryId);
        List<Attachment> photos = reviewAttachmentService.resolveForLink(buyerId, attachmentIds, review.getId());
        review.edit(rating, content, keywordIds);
        reviewAttachmentService.replaceLinks(photos, review.getId(), buyerId);
        eventPublisher.publishEvent(new ReviewChangedEvent(review.getProductId()));
        log.info("[Review] 수정 reviewPublicId={} buyerId={}", reviewPublicId, buyerId);
    }

    /**
     * 작성자 삭제(soft delete). 행·사진 연결은 남기고(관리자 추적) 공개 목록·사진 서빙에서 사라진다. 품목은 다시 리뷰를 쓸 수 없다.
     *
     * @throws ReviewNotFoundException 미존재·이미 삭제·타인 리뷰(404)
     */
    public void delete(Long buyerId, String reviewPublicId) {
        Review review = requireOwnReviewForUpdate(buyerId, reviewPublicId);
        review.markDeleted();
        eventPublisher.publishEvent(new ReviewChangedEvent(review.getProductId()));
        log.info("[Review] 삭제 reviewPublicId={} buyerId={}", reviewPublicId, buyerId);
    }

    /**
     * 위반 제약이 uk_review_order_item인지. Hibernate가 드라이버 메시지에서 뽑은 제약 이름으로 판별한다(DB에 따라 "테이블.제약" 형태라 접미 비교).
     */
    static boolean isOrderItemDuplicate(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                String name = constraintViolation.getConstraintName();
                return name != null && (name.equalsIgnoreCase(ORDER_ITEM_UNIQUE_KEY)
                        || name.toLowerCase(Locale.ROOT).endsWith("." + ORDER_ITEM_UNIQUE_KEY));
            }
        }
        return false;
    }

    /** 락을 잡고 작성자 대조. 타인 리뷰도 미존재와 같은 404로 은닉한다. */
    private Review requireOwnReviewForUpdate(Long buyerId, String reviewPublicId) {
        Review review = reviewRepository.findByPublicIdForUpdate(reviewPublicId)
                .orElseThrow(() -> new ReviewNotFoundException("리뷰를 찾을 수 없습니다: " + reviewPublicId));
        if (!review.isWrittenBy(buyerId)) {
            throw new ReviewNotFoundException("리뷰를 찾을 수 없습니다: " + reviewPublicId);
        }
        return review;
    }

    /** 품목 → 주문 → 구매자 대조. OrderItem은 Order getter를 막아 두었으므로 주문 id를 따로 조회한다. */
    private OrderItem requireOwnedItem(Long buyerId, String orderItemPublicId) {
        OrderItem item = orderItemRepository.findByPublicId(orderItemPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문 품목을 찾을 수 없습니다: " + orderItemPublicId));
        Long buyerOfItem = orderItemRepository.findOrderIdById(item.getId())
                .flatMap(orderRepository::findById)
                .map(Order::getBuyerId)
                .orElse(null);
        if (!buyerId.equals(buyerOfItem)) {
            throw new OrderNotFoundException("주문 품목을 찾을 수 없습니다: " + orderItemPublicId);
        }
        return item;
    }

    /**
     * 키워드 code → id. 기본 세트(모든 상품)와 상품 최상위 카테고리 세트에 속한 것만 허용한다.
     *
     * @throws MalformedRequestException 중복 code·쓸 수 없는 code(400)
     */
    List<Long> resolveKeywordIds(List<String> keywordCodes, Long topCategoryId) {
        if (keywordCodes.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new HashSet<>(keywordCodes);
        if (distinct.size() != keywordCodes.size()) {
            throw new MalformedRequestException("keywordCodes에 중복된 키워드가 있습니다.");
        }
        List<ReviewKeyword> usable = reviewKeywordRepository.findUsableByCodeIn(distinct, topCategoryId);
        if (usable.size() != distinct.size()) {
            Set<String> unknown = new HashSet<>(distinct);
            usable.forEach(keyword -> unknown.remove(keyword.getCode()));
            throw new MalformedRequestException("이 상품에 쓸 수 없는 키워드입니다: " + unknown);
        }
        return usable.stream().map(ReviewKeyword::getId).toList();
    }
}
