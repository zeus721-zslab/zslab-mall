package com.zslab.mall.inquiry;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 문의 통합 테스트 공용 시드·정리(Track 106-4). ProductQuestionFixture 방식을 따른다 — 클래스마다 겹치지 않는 id 대역 · FOREIGN_KEY_CHECKS=0 ·
 * try-finally 복원 · 실 커밋. 정리는 대역 구매자의 문의(API가 만든 자동 증가 id 포함)·감사 행과 시드 행을 함께 지운다.
 *
 * <p>모든 SQL은 ? positional 바인딩 + 정적 문자열이다(문자열 concat 없음·SQL injection 위험 없음).
 */
public final class InquiryFixture {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public InquiryFixture(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 사용자 행(이메일 지정 가능 · null이면 탈퇴 비식별화와 같은 상태). */
    public void seedUser(long userId, String email) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO `user` (id, public_id, email, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                userId, pid("usr_", "INU" + userId), email));
    }

    /** 구매자 주문 행(품목 없이 주문 헤더만). public_id를 돌려준다. */
    public String seedOrder(long orderId, long buyerId) {
        String orderPublicId = pid("ord_", "INO" + orderId);
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, discount_amount, shipping_fee, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PAID', 10000, 0, 0, NOW(6), NOW(6))",
                orderId, orderPublicId, buyerId, "ORDIN" + orderId));
        return orderPublicId;
    }

    /**
     * 문의 행 직접 삽입. answer가 null이면 미답변이고, 값이 있으면 답변 3컬럼을 함께 채운다(V43 chk_inquiry_answer). checked가 true면
     * 답변 확인 시각도 채운다. public_id를 돌려준다.
     */
    public String insertInquiry(long inquiryId, long buyerId, Long orderId, String category, String content, String answer,
            Long answeredBy, boolean checked, LocalDateTime createdAt) {
        String inquiryPublicId = pid("inq_", "INQ" + inquiryId);
        LocalDateTime answeredAt = answer == null ? null : createdAt.plusMinutes(1);
        LocalDateTime checkedAt = answer != null && checked ? createdAt.plusMinutes(2) : null;
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO inquiry (id, public_id, buyer_id, order_id, category, content, answer_content, answered_at, answered_by, "
                        + "answer_checked_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                inquiryId, inquiryPublicId, buyerId, orderId, category, content, answer, answeredAt,
                answer == null ? null : answeredBy, checkedAt, createdAt, createdAt));
        return inquiryPublicId;
    }

    public void markInquiryDeleted(long inquiryId) {
        jdbc.update("UPDATE inquiry SET deleted_at = NOW(6) WHERE id = ?", inquiryId);
    }

    /** 대역 [fromId, toId]의 구매자에 딸린 문의·감사 행과 시드 행을 지운다. */
    public void cleanup(long fromId, long toId) {
        withoutForeignKeys(() -> {
            jdbc.update("DELETE FROM audit_log WHERE target_type = 'INQUIRY' AND target_id IN "
                    + "(SELECT id FROM inquiry WHERE buyer_id BETWEEN ? AND ?)", fromId, toId);
            jdbc.update("DELETE FROM inquiry WHERE buyer_id BETWEEN ? AND ?", fromId, toId);
            jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", fromId, toId);
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
