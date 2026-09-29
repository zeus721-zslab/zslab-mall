package com.zslab.mall.review.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.review.controller.response.KeywordResponse;
import com.zslab.mall.review.controller.response.ReviewItemResponse;
import com.zslab.mall.review.controller.response.ReviewOwnerResponse;
import com.zslab.mall.review.controller.response.ReviewPhotoResponse;
import com.zslab.mall.review.controller.response.ReviewSummaryResponse;
import com.zslab.mall.review.entity.ProductReviewSummary;
import com.zslab.mall.review.entity.Review;
import com.zslab.mall.review.entity.ReviewKeyword;
import com.zslab.mall.review.enums.ReviewSort;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.review.exception.ReviewNotFoundException;
import com.zslab.mall.review.repository.KeywordCountProjection;
import com.zslab.mall.review.repository.ProductRatingProjection;
import com.zslab.mall.review.repository.ProductReviewSummaryRepository;
import com.zslab.mall.review.repository.RatingCountProjection;
import com.zslab.mall.review.repository.ReviewKeywordIdProjection;
import com.zslab.mall.review.repository.ReviewKeywordRepository;
import com.zslab.mall.review.repository.ReviewRepository;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.repository.SellerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 리뷰 조회(Track 106-1): 상품별 목록·요약. 비로그인도 조회할 수 있고, 구매자로 로그인했으면 목록 항목마다 "내가 도움됐어요 눌렀는지"를
 * 붙인다. 공개 = status VISIBLE + 미삭제(엔티티 @SQLRestriction). 페이지 항목의 키워드·사진·도움됐어요는 페이지 리뷰 id로 한 번씩 배치 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 50;
    /** 요약의 최근 사진 수. */
    static final int RECENT_PHOTO_LIMIT = 10;
    private static final int AVERAGE_SCALE = 1;

    private final ReviewRepository reviewRepository;
    private final ReviewKeywordRepository reviewKeywordRepository;
    private final ProductReviewSummaryRepository productReviewSummaryRepository;
    private final ProductRepository productRepository;
    private final AttachmentRepository attachmentRepository;
    private final ImageUploadService imageUploadService;
    private final SellerRepository sellerRepository;

    public ReviewQueryService(ReviewRepository reviewRepository, ReviewKeywordRepository reviewKeywordRepository,
            ProductReviewSummaryRepository productReviewSummaryRepository, ProductRepository productRepository,
            AttachmentRepository attachmentRepository, ImageUploadService imageUploadService, SellerRepository sellerRepository) {
        this.reviewRepository = reviewRepository;
        this.reviewKeywordRepository = reviewKeywordRepository;
        this.productReviewSummaryRepository = productReviewSummaryRepository;
        this.productRepository = productRepository;
        this.attachmentRepository = attachmentRepository;
        this.imageUploadService = imageUploadService;
        this.sellerRepository = sellerRepository;
    }

    /**
     * 상품 공개 리뷰 목록.
     *
     * @param keywordCode 선택 키워드 필터(없으면 null)
     * @param optionLabel 옵션 라벨 필터(작성 시점 스냅샷과 정확히 같은 값·없으면 null)
     * @param viewerId    로그인 구매자 id(익명이면 null — 항목의 helpedByMe를 채우지 않는다)
     * @throws ProductNotFoundException 상품 미존재·삭제·비노출(404)
     * @throws MalformedRequestException 알 수 없는 키워드 code(400)
     */
    public PagedResponse<ReviewItemResponse> list(String productPublicId, String keywordCode, String optionLabel, boolean photoOnly,
            ReviewSort sort, int page, int size, Long viewerId) {
        Product product = requireProduct(productPublicId);
        Long keywordId = keywordCode == null ? null : reviewKeywordRepository.findByCode(keywordCode)
                .map(ReviewKeyword::getId)
                .orElseThrow(() -> new MalformedRequestException("알 수 없는 키워드입니다: " + keywordCode));
        Page<Review> reviews = reviewRepository.findPublicPage(product.getId(), ReviewStatus.VISIBLE, keywordId, optionLabel, photoOnly,
                PageRequest.of(Math.max(page, 0), clampSize(size), sortOf(sort)));
        List<Long> reviewIds = reviews.getContent().stream().map(Review::getId).toList();
        Map<Long, List<KeywordResponse>> keywordsByReview = keywordsByReviewId(reviewIds);
        Map<Long, List<ReviewPhotoResponse>> photosByReview = photosByReviewId(reviewIds);
        Set<Long> helped = viewerId == null || reviewIds.isEmpty()
                ? Set.of()
                : new HashSet<>(reviewRepository.findHelpedReviewIds(viewerId, reviewIds));
        return PagedResponse.from(reviews.map(review -> new ReviewItemResponse(
                review.getPublicId(),
                review.getRating(),
                review.getContent(),
                review.getOptionLabel(),
                keywordsByReview.getOrDefault(review.getId(), List.of()),
                photosByReview.getOrDefault(review.getId(), List.of()),
                review.getHelpfulCount(),
                viewerId != null ? helped.contains(review.getId()) : null,
                review.getCreatedAt())));
    }

    /**
     * 상품 리뷰 요약(별점 분포·키워드 집계·최근 사진·한 줄 요약).
     *
     * @throws ProductNotFoundException 상품 미존재·삭제·비노출(404)
     */
    public ReviewSummaryResponse summary(String productPublicId) {
        Product product = requireProduct(productPublicId);
        Map<Integer, Long> countByRating = reviewRepository.countByRating(product.getId(), ReviewStatus.VISIBLE).stream()
                .collect(Collectors.toMap(RatingCountProjection::getRating, RatingCountProjection::getReviewCount));
        List<ReviewSummaryResponse.RatingCount> distribution = new ArrayList<>();
        long reviewCount = 0;
        long ratingSum = 0;
        for (int rating = Review.MAX_RATING; rating >= Review.MIN_RATING; rating--) {
            long count = countByRating.getOrDefault(rating, 0L);
            distribution.add(new ReviewSummaryResponse.RatingCount(rating, count));
            reviewCount += count;
            ratingSum += count * rating;
        }
        Double averageRating = reviewCount == 0 ? null : roundAverage((double) ratingSum / reviewCount);
        List<ReviewSummaryResponse.RecentPhoto> recentPhotos = reviewRepository
                .findRecentPhotos(product.getId(), ReviewStatus.VISIBLE, PageRequest.of(0, RECENT_PHOTO_LIMIT)).stream()
                .map(photo -> new ReviewSummaryResponse.RecentPhoto(photo.getReviewPublicId(), photo.getFilePath(),
                        imageUploadService.thumbnailUrlFor(photo.getFilePath())))
                .toList();
        String summaryText = productReviewSummaryRepository.findById(product.getId())
                .map(ProductReviewSummary::getSummaryText)
                .orElse(null);
        return new ReviewSummaryResponse(reviewCount, averageRating, distribution, keywordCounts(product.getId()), recentPhotos,
                summaryText);
    }

    /**
     * 작성자 본인 리뷰 단건(숨김 포함·삭제 제외).
     *
     * @throws ReviewNotFoundException 미존재·삭제·타인 리뷰(404 은닉)
     */
    public ReviewOwnerResponse getOwn(Long buyerId, String reviewPublicId) {
        Review review = reviewRepository.findByPublicId(reviewPublicId)
                .filter(found -> found.isWrittenBy(buyerId))
                .orElseThrow(() -> new ReviewNotFoundException("리뷰를 찾을 수 없습니다: " + reviewPublicId));
        List<ReviewOwnerResponse.Photo> photos = attachmentRepository
                .findByTargetTypeAndTargetIdOrderByDisplayOrderAsc(PolymorphicTargetType.REVIEW, review.getId()).stream()
                .map(photo -> new ReviewOwnerResponse.Photo(photo.getPublicId(), photo.getFilePath(),
                        imageUploadService.thumbnailUrlFor(photo.getFilePath())))
                .toList();
        return new ReviewOwnerResponse(
                review.getPublicId(),
                review.getRating(),
                review.getContent(),
                review.getOptionLabel(),
                keywordsByReviewId(List.of(review.getId())).getOrDefault(review.getId(), List.of()),
                photos,
                review.getStatus(),
                review.getHiddenReason(),
                review.getStatus() == ReviewStatus.VISIBLE,
                review.getCreatedAt());
    }

    /** 키워드별 선택 수(공개 리뷰·많은 순·동률은 표시 순서). */
    List<ReviewSummaryResponse.KeywordCount> keywordCounts(Long productId) {
        List<KeywordCountProjection> counts = reviewRepository.countByKeyword(productId, ReviewStatus.VISIBLE);
        Map<Long, ReviewKeyword> keywords = keywordsById(counts.stream().map(KeywordCountProjection::getKeywordId).toList());
        return counts.stream()
                .filter(count -> keywords.containsKey(count.getKeywordId()))
                .sorted(Comparator.comparing(KeywordCountProjection::getReviewCount).reversed()
                        .thenComparing(count -> keywords.get(count.getKeywordId()).getDisplayOrder()))
                .map(count -> {
                    ReviewKeyword keyword = keywords.get(count.getKeywordId());
                    return new ReviewSummaryResponse.KeywordCount(keyword.getCode(), keyword.getLabel(), count.getReviewCount());
                })
                .toList();
    }

    /**
     * 상품 목록 카드 별점(결정 5 — 조회 시 배치 집계). 공개 리뷰 기준 상품별 리뷰 수·평균을 1쿼리로 모은다. 리뷰가 없는 상품은 맵에 없다.
     */
    public Map<Long, ProductRating> ratingsByProductId(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return reviewRepository.aggregateRatingByProductIdIn(productIds, ReviewStatus.VISIBLE).stream()
                .collect(Collectors.toMap(ProductRatingProjection::getProductId,
                        rating -> new ProductRating(rating.getReviewCount(), roundAverage(rating.getAverageRating()))));
    }

    /** 상품 1개의 공개 리뷰 수·평균 별점(소수 첫째 자리). */
    public record ProductRating(long reviewCount, double averageRating) {
    }

    /** 평균 별점 소수 첫째 자리 반올림(목록 카드·요약 공통). */
    static double roundAverage(double average) {
        return BigDecimal.valueOf(average).setScale(AVERAGE_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 상품 상세({@code ProductCatalogService.getProduct})와 같은 노출 판정 — 판매중·판매중지 상품이고 판매자가 ACTIVE일 때만 리뷰를 보여준다.
     * 그 밖(미존재·삭제·다른 상태·판매자 비-ACTIVE)은 상세처럼 404로 은닉한다(리뷰 조회로 비노출 상품의 존재가 드러나지 않게).
     */
    private Product requireProduct(String productPublicId) {
        Product product = productRepository.findByPublicId(productPublicId)
                .filter(found -> found.getStatus() == ProductStatus.SALE || found.getStatus() == ProductStatus.STOPPED)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        sellerRepository.findById(product.getSellerId())
                .filter(seller -> seller.getStatus() == SellerStatus.ACTIVE)
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: " + productPublicId));
        return product;
    }

    private Map<Long, List<KeywordResponse>> keywordsByReviewId(List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        List<ReviewKeywordIdProjection> rows = reviewRepository.findKeywordIdsByReviewIdIn(reviewIds);
        Map<Long, ReviewKeyword> keywords = keywordsById(rows.stream().map(ReviewKeywordIdProjection::getKeywordId).toList());
        Map<Long, List<KeywordResponse>> result = new HashMap<>();
        rows.stream()
                .filter(row -> keywords.containsKey(row.getKeywordId()))
                .sorted(Comparator.comparing(row -> keywords.get(row.getKeywordId()).getDisplayOrder()))
                .forEach(row -> {
                    ReviewKeyword keyword = keywords.get(row.getKeywordId());
                    result.computeIfAbsent(row.getReviewId(), key -> new ArrayList<>())
                            .add(new KeywordResponse(keyword.getCode(), keyword.getLabel()));
                });
        return result;
    }

    private Map<Long, List<ReviewPhotoResponse>> photosByReviewId(List<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReviewPhotoResponse>> result = new HashMap<>();
        for (Attachment photo : attachmentRepository.findByTargetTypeAndTargetIdInOrderByTargetIdAscDisplayOrderAsc(
                PolymorphicTargetType.REVIEW, reviewIds)) {
            result.computeIfAbsent(photo.getTargetId(), key -> new ArrayList<>())
                    .add(new ReviewPhotoResponse(photo.getFilePath(), imageUploadService.thumbnailUrlFor(photo.getFilePath())));
        }
        return result;
    }

    private Map<Long, ReviewKeyword> keywordsById(Collection<Long> keywordIds) {
        if (keywordIds.isEmpty()) {
            return Map.of();
        }
        return reviewKeywordRepository.findByIdIn(new HashSet<>(keywordIds)).stream()
                .collect(Collectors.toMap(ReviewKeyword::getId, Function.identity()));
    }

    private static Sort sortOf(ReviewSort sort) {
        Sort latest = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return sort == ReviewSort.HELPFUL ? Sort.by(Sort.Order.desc("helpfulCount")).and(latest) : latest;
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
