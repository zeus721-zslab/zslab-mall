package com.zslab.mall.productquestion;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 상품 질문 통합 테스트 공용 시드·정리(Track 106-2). ReviewFixture의 사용자·카탈로그 시드 방식을 따른다 — 클래스마다 겹치지 않는 id 대역을 쓰고,
 * FOREIGN_KEY_CHECKS=0 · try-finally 복원 · 실 커밋이다. 정리는 대역의 상품·구매자에 딸린 질문(API가 만든 자동 증가 id 포함)·리뷰·감사 행을 함께 지운다.
 *
 * <p>모든 SQL은 ? positional 바인딩 + 정적 문자열이다(문자열 concat 없음·SQL injection 위험 없음).
 */
public final class ProductQuestionFixture {

    /** 숨김 상태로 직접 넣는 질문의 사유(V41 chk_product_question_hidden_reason 충족용). */
    public static final String HIDDEN_REASON = "테스트 숨김";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ProductQuestionFixture(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public void seedUser(long userId) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", "PQU" + userId)));
    }

    /** 셀러(상태 지정) + 상품(판매중·설명 지정). category 행은 만들지 않는다(FK 끔). 상품 public_id를 돌려준다. */
    public String seedCatalog(long sellerId, String sellerStatus, long productId, String description) {
        String productPublicId = pid("prd_", "PQP" + productId);
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                    + "VALUES (?, ?, '질문셀러', '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", "PQS" + sellerId), sellerStatus);
            seedProduct(productId, sellerId, "SALE", description);
        });
        return productPublicId;
    }

    /** 기존 셀러에 상품 1개를 더한다(상태 지정). 상품 public_id를 돌려준다. */
    public String seedProduct(long productId, long sellerId, String productStatus, String description) {
        String productPublicId = pid("prd_", "PQP" + productId);
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO product (id, public_id, seller_id, category_id, name, description, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, '질문상품', ?, ?, 10000, NOW(6), NOW(6))",
                productId, productPublicId, sellerId, description, productStatus));
        return productPublicId;
    }

    /** 셀러 소속 사용자(user 행 + seller_user SELLER_OWNER). 셀러 쿠키 인증·답변자 id용. */
    public void seedSellerUser(long userId, long sellerId) {
        seedUser(userId);
        withoutForeignKeys(() -> jdbc.update("INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", sellerId, userId));
    }

    /**
     * 질문 행 직접 삽입. answer가 null이면 미답변이고, 값이 있으면 답변 3컬럼을 함께 채운다(V41 chk_product_question_answer).
     * public_id를 돌려준다.
     */
    public String insertQuestion(long questionId, long productId, long buyerId, String content, String status, String answer,
            Long answeredBy, LocalDateTime createdAt) {
        String questionPublicId = pid("pqn_", "PQQ" + questionId);
        String hiddenReason = "HIDDEN".equals(status) ? HIDDEN_REASON : null;
        LocalDateTime answeredAt = answer == null ? null : createdAt.plusMinutes(1);
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, hidden_reason, answer_content, "
                        + "answered_at, answered_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                questionId, questionPublicId, productId, buyerId, content, status, hiddenReason, answer, answeredAt,
                answer == null ? null : answeredBy, createdAt, createdAt));
        return questionPublicId;
    }

    /** 리뷰 행 직접 삽입(즉시 답 후보용·주문 품목 없이 FK 끔). public_id를 돌려준다. */
    public String insertReview(long reviewId, long productId, long buyerId, String content, String status, LocalDateTime createdAt) {
        String reviewPublicId = pid("rvw_", "PQR" + reviewId);
        String hiddenReason = "HIDDEN".equals(status) ? HIDDEN_REASON : null;
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO review (id, public_id, order_item_id, product_id, buyer_id, rating, content, status, hidden_reason, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?)",
                reviewId, reviewPublicId, reviewId, productId, buyerId, content, status, hiddenReason, createdAt, createdAt));
        return reviewPublicId;
    }

    public void markQuestionDeleted(long questionId) {
        jdbc.update("UPDATE product_question SET deleted_at = NOW(6) WHERE id = ?", questionId);
    }

    public void markReviewDeleted(long reviewId) {
        jdbc.update("UPDATE review SET deleted_at = NOW(6) WHERE id = ?", reviewId);
    }

    /** 대역 [fromId, toId]의 상품·구매자에 딸린 질문·리뷰·감사 행과 시드 행을 지운다. */
    public void cleanup(long fromId, long toId) {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'PRODUCT_QUESTION' AND target_id IN "
                    + "(SELECT id FROM product_question WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?)",
                    fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM product_question WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?",
                    fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM review WHERE product_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM seller_user WHERE user_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", fromId, toId);
        });
    }

    /** FK 검사를 끈 트랜잭션에서 실행한다(시드가 더미 FK 값을 쓰므로). */
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
