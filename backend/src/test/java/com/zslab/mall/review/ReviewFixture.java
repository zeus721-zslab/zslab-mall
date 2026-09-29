package com.zslab.mall.review;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리뷰 통합 테스트 공용 시드·정리(Track 106-1). 클래스마다 겹치지 않는 id 대역을 쓰고, 정리는 그 대역의 상품·구매자에 딸린 리뷰 행
 * (API로 만들어 자동 증가 id인 리뷰·첨부 포함)을 함께 지운다. BuyerOrderConfirmControllerIntegrationTest의 시드 형태를 따른다
 * (FOREIGN_KEY_CHECKS=0 · try-finally 복원 · 실 커밋).
 *
 * <p>모든 SQL은 ? positional 바인딩 + 정적 문자열이다(문자열 concat 없음·SQL injection 위험 없음).
 */
public final class ReviewFixture {

    /** 숨김 상태로 직접 넣는 리뷰의 사유(V40 chk_review_hidden_reason 충족용). */
    public static final String HIDDEN_REASON = "테스트 숨김";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReviewFixture(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public void seedUser(long userId) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", "RVU" + userId)));
    }

    /** 셀러·상품(최상위 카테고리 categoryId)·옵션 1개. category 행은 만들지 않는다(FK 끔·키워드 소속은 id만 본다). */
    public void seedCatalog(long sellerId, long productId, long variantId, long categoryId) {
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, '리뷰셀러', '대표', 'ACTIVE', NOW(6), NOW(6))", sellerId, pid("slr_", "RVS" + sellerId));
            jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, '리뷰상품', 'SALE', 10000, NOW(6), NOW(6))",
                    productId, pid("prd_", "RVP" + productId), sellerId, categoryId);
            jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, status, "
                    + "is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                    variantId, pid("var_", "RVV" + variantId), productId, "RV" + variantId, categoryId);
        });
    }

    /** 주문 1건 + 품목 1개(order.status = 품목 상태). 품목 public_id를 돌려준다. */
    public String seedItem(long orderId, long itemId, long buyerId, long productId, long variantId, long sellerId,
            String itemStatus, String optionLabel) {
        String itemPublicId = pid("oit_", "RVI" + itemId);
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), NOW(6))",
                    orderId, pid("ord_", "RVO" + orderId), buyerId, "ORDRV" + orderId, itemStatus);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, option_label, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, ?, NOW(6), NOW(6), '리뷰상품', 1000)",
                    itemId, itemPublicId, orderId, productId, variantId, sellerId, itemStatus, optionLabel);
        });
        return itemPublicId;
    }

    /** 기존 주문에 품목 1개를 더한다. 품목 public_id를 돌려준다. */
    public String seedItemInOrder(long orderId, long itemId, long productId, long variantId, long sellerId, String itemStatus) {
        String itemPublicId = pid("oit_", "RVI" + itemId);
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                        + "total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, 10000, 10000, ?, NOW(6), NOW(6), '리뷰상품', 1000)",
                itemId, itemPublicId, orderId, productId, variantId, sellerId, itemStatus));
        return itemPublicId;
    }

    /** 리뷰 행 직접 삽입(조회·집계 테스트용). public_id를 돌려준다. */
    public String insertReview(long reviewId, long itemId, long productId, long buyerId, int rating, String status,
            String optionLabel, int helpfulCount, LocalDateTime createdAt) {
        String reviewPublicId = pid("rvw_", "RVR" + reviewId);
        // chk_review_hidden_reason(V40): 숨김 행은 사유가 있어야 한다.
        String hiddenReason = "HIDDEN".equals(status) ? HIDDEN_REASON : null;
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO review (id, public_id, order_item_id, product_id, buyer_id, rating, content, option_label, status, "
                        + "hidden_reason, helpful_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, '테스트 리뷰 본문', ?, ?, ?, ?, ?, ?)",
                reviewId, reviewPublicId, itemId, productId, buyerId, rating, optionLabel, status, hiddenReason, helpfulCount, createdAt,
                createdAt));
        return reviewPublicId;
    }

    public void selectKeyword(long reviewId, String keywordCode) {
        jdbc.update("INSERT INTO review_keyword_selection (review_id, keyword_id) SELECT ?, id FROM review_keyword WHERE code = ?",
                reviewId, keywordCode);
    }

    /** 카테고리 전용 키워드(테스트 대역 id). */
    public void insertCategoryKeyword(long keywordId, String code, long topCategoryId) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO review_keyword (id, code, label, group_code, top_category_id, display_order, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PRODUCT', ?, 100, NOW(6), NOW(6))",
                keywordId, code, code, topCategoryId));
    }

    /** 리뷰에 연결된 사진 행(파일 없이 행만). file_path를 돌려준다. */
    public String insertReviewPhoto(long attachmentId, long reviewId, long uploaderId, String fileKey, int displayOrder) {
        String filePath = "/api/v1/files/" + fileKey;
        jdbc.update("INSERT INTO attachment (id, public_id, target_type, target_id, file_name, file_path, mime_type, file_size, "
                        + "display_order, uploaded_by, created_at, updated_at) VALUES (?, ?, 'REVIEW', ?, 'p.jpg', ?, 'image/jpeg', 1, ?, ?, "
                        + "NOW(6), NOW(6))",
                attachmentId, pid("att_", "RVA" + attachmentId), reviewId, filePath, displayOrder, uploaderId);
        return filePath;
    }

    /**
     * 대역 [fromId, toId]의 상품·구매자에 딸린 리뷰 데이터와 시드 행을 지운다. API가 만든 리뷰·첨부는 자동 증가 id라 상품·업로더 기준으로 찾는다.
     */
    public void cleanup(long fromId, long toId) {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM review_helpful WHERE user_id BETWEEN ? AND ? OR review_id IN "
                    + "(SELECT id FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?)",
                    fromId, toId, fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM review_keyword_selection WHERE review_id IN "
                    + "(SELECT id FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?)",
                    fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'REVIEW' AND target_id IN "
                    + "(SELECT id FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?)",
                    fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM attachment WHERE uploaded_by BETWEEN ? AND ? OR id BETWEEN ? AND ? "
                    + "OR (target_type = 'REVIEW' AND target_id IN "
                    + "(SELECT id FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?))",
                    fromId, toId, fromId, toId, fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?", fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM product_review_summary WHERE product_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM review_keyword WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM product_variant WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", fromId, toId);
        });
    }

    /** FK 검사를 끈 트랜잭션에서 실행한다(시드가 더미 FK 값을 쓰므로 시드 행을 고치는 테스트도 이 경로로 쓴다). */
    public void withoutForeignKeys(Runnable work) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                work.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    public static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
