package com.zslab.mall.review.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.review.controller.response.AdminReviewResponse;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.repository.ReviewRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 리뷰 목록(Track 106-1·숨김 판단용). 상태 필터(없으면 전체)·최신순·삭제 제외. 페이지 리뷰의 상품·사진은 한 번씩 배치 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class AdminReviewQueryService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final AttachmentRepository attachmentRepository;

    public AdminReviewQueryService(ReviewRepository reviewRepository, ProductRepository productRepository,
            AttachmentRepository attachmentRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.attachmentRepository = attachmentRepository;
    }

    /** @param status 상태 필터(없으면 null = 전체) */
    public PagedResponse<AdminReviewResponse> list(ReviewStatus status, int page, int size) {
        Page<Review> reviews = reviewRepository.findForAdmin(status, PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<Review> content = reviews.getContent();
        Map<Long, Product> products = content.isEmpty() ? Map.of()
                : productRepository.findByIdIn(content.stream().map(Review::getProductId).distinct().toList()).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, List<String>> photoUrls = new HashMap<>();
        if (!content.isEmpty()) {
            for (Attachment photo : attachmentRepository.findByTargetTypeAndTargetIdInOrderByTargetIdAscDisplayOrderAsc(
                    PolymorphicTargetType.REVIEW, content.stream().map(Review::getId).toList())) {
                photoUrls.computeIfAbsent(photo.getTargetId(), key -> new ArrayList<>()).add(photo.getFilePath());
            }
        }
        return PagedResponse.from(reviews.map(review -> {
            Product product = products.get(review.getProductId());
            return new AdminReviewResponse(
                    review.getPublicId(),
                    product == null ? null : product.getPublicId(),
                    product == null ? null : product.getName(),
                    review.getRating(),
                    review.getContent(),
                    review.getOptionLabel(),
                    review.getStatus(),
                    review.getHelpfulCount(),
                    photoUrls.getOrDefault(review.getId(), List.of()),
                    review.getCreatedAt());
        }));
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return ReviewQueryService.DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, ReviewQueryService.MAX_PAGE_SIZE);
    }
}
