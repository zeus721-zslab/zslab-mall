package com.zslab.mall.demoseed;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데모 시더 통합 테스트 시드·정리(D-244). ProductQuestionFixture 방식 — 전용 id 대역 · FOREIGN_KEY_CHECKS=0 · try-finally 복원 · 실 커밋.
 * 정리는 대역 상품의 질문(시더가 만든 자동 증가 id 포함)·대역 행위자의 DEMO_SEED 감사·시드 행을 지운다.
 *
 * <p>모든 SQL은 ? positional 바인딩 + 정적 문자열이다(문자열 concat 없음·SQL injection 위험 없음).
 */
final class DemoSeedFixture {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    DemoSeedFixture(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** user 행 + 역할 1개(role 코드). withdrawn이면 탈퇴 시각을 채운다. */
    void seedUser(long userId, String email, String roleCode, LocalDateTime createdAt, boolean withdrawn) {
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO `user` (id, public_id, email, withdrawn_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    userId, pid("usr_", "DSU" + userId), email, withdrawn ? createdAt : null, createdAt, createdAt);
            jdbc.update("INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", userId, roleCode);
        });
    }

    void addRole(long userId, String roleCode) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO user_role (user_id, role_id, created_at) SELECT ?, id, NOW(6) FROM role WHERE code = ?", userId, roleCode));
    }

    void seedSeller(long sellerId, String companyName, String status) {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, '대표', ?, NOW(6), NOW(6))", sellerId, pid("slr_", "DSS" + sellerId), companyName, status));
    }

    void seedOwner(long userId, long sellerId) {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO seller_user (seller_id, user_id, role_id, created_at, updated_at) "
                + "SELECT ?, ?, id, NOW(6), NOW(6) FROM role WHERE code = 'SELLER_OWNER'", sellerId, userId));
    }

    /** 상품(카테고리 행 없음 → 대체 문구 세트). 상품 public_id를 돌려준다. */
    String seedProduct(long productId, long sellerId, String status, LocalDateTime createdAt) {
        String productPublicId = pid("prd_", "DSP" + productId);
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 999999, '시더상품', ?, 10000, ?, ?)",
                productId, productPublicId, sellerId, status, createdAt, createdAt));
        return productPublicId;
    }

    /** 질문 직접 삽입(answer null = 미답변 · HIDDEN이면 사유 채움). */
    void insertQuestion(long questionId, long productId, long buyerId, String content, String status, String answer, Long answeredBy,
            LocalDateTime createdAt) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO product_question (id, public_id, product_id, buyer_id, content, status, hidden_reason, answer_content, "
                        + "answered_at, answered_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                questionId, pid("pqn_", "DSQ" + questionId), productId, buyerId, content, status,
                "HIDDEN".equals(status) ? "테스트 숨김" : null, answer, answer == null ? null : createdAt.plusMinutes(1),
                answer == null ? null : answeredBy, createdAt, createdAt));
    }

    /** 주문 1건 + 품목 1건(상태·구매확정 시각 지정). 품목 public_id를 돌려준다. 주문 id = 품목 id. */
    String seedOrderItem(long itemId, long buyerId, long productId, long sellerId, String itemStatus, LocalDateTime confirmedAt) {
        String itemPublicId = pid("oit_", "DSI" + itemId);
        withoutForeignKeys(() -> {
            jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10000, 0, 0, NOW(6), NOW(6))",
                    itemId, pid("ord_", "DSO" + itemId), buyerId, "ORDDS" + itemId, itemStatus);
            jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, quantity, unit_price, "
                    + "total_price, item_status, confirmed_at, created_at, updated_at, product_name, commission_rate) "
                    + "VALUES (?, ?, ?, ?, 1, ?, 1, 10000, 10000, ?, ?, NOW(6), NOW(6), '시더상품', 1000)",
                    itemId, itemPublicId, itemId, productId, sellerId, itemStatus, confirmedAt);
        });
        return itemPublicId;
    }

    /** 리뷰 행 직접 삽입(품목 FK 끔 · deleted면 삭제 시각 채움 · HIDDEN이면 사유 채움). */
    void insertReview(long reviewId, long orderItemId, long productId, long buyerId, String content, String status, boolean deleted) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO review (id, public_id, order_item_id, product_id, buyer_id, rating, content, status, hidden_reason, "
                        + "deleted_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 5, ?, ?, ?, ?, NOW(6), NOW(6))",
                reviewId, pid("rvw_", "DSR" + reviewId), orderItemId, productId, buyerId, content, status,
                "HIDDEN".equals(status) ? "테스트 숨김" : null, deleted ? LocalDateTime.now() : null));
    }

    void cleanup(long fromId, long toId) {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'DEMO_SEED' AND actor_user_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM review_keyword_selection WHERE review_id IN "
                    + "(SELECT id FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?)", fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM review WHERE product_id BETWEEN ? AND ? OR buyer_id BETWEEN ? AND ?", fromId, toId, fromId, toId);
            jdbc.update("DELETE FROM product_review_summary WHERE product_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM product_question WHERE product_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM seller_user WHERE user_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM product WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM user_role WHERE user_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", fromId, toId);
        });
    }

    private void withoutForeignKeys(Runnable work) {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                work.run();
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
