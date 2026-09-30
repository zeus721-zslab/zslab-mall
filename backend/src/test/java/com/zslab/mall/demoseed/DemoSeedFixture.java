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

    void cleanup(long fromId, long toId) {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'DEMO_SEED' AND actor_user_id BETWEEN ? AND ?", fromId, toId);
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
