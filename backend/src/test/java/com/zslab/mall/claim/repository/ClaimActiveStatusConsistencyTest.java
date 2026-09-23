package com.zslab.mall.claim.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.support.AbstractIntegrationTest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "진행 중(활성)" 판정 집합 일치 고정 테스트(Track 101-B 외부 검토 반영).
 *
 * <p><b>왜 필요한가</b>: 활성 = REQUESTED·APPROVED라는 기준이 Java 한 곳({@link ClaimStatus#isActive()})과 JPQL 네 곳
 * ({@code existsActiveByOrderItemId}·{@code existsActiveByBuyerId}·{@code countActiveBySellerId}·
 * {@code findActiveByOrderItemIdIn})에 따로 적혀 있다. 한쪽만 고치면 화면·가드가 조용히 갈린다 — 예를 들어 JPQL에서만
 * 상태를 늘리면 구매자 탈퇴 가드는 막는데 주문 카드 배지는 뜨지 않는 식이다. 이 테스트는 <b>실제 행을 상태별로 넣고</b>
 * enum 판정과 각 쿼리 결과를 상태마다 대조하므로, 어느 한쪽만 바뀌면 먼저 깨진다.
 *
 * <p>대조 대상은 {@link ClaimStatus#values()} 전부라 상태가 늘어나도 자동으로 포함된다(케이스 추가 누락 없음).
 * 시드는 상태마다 독립 그래프(구매자·셀러·주문·품목·클레임 1:1:1:1:1)를 만든다 — {@code countActiveBySellerId}가
 * 셀러 축 집계라 셀러를 공유하면 상태 간 간섭이 생기기 때문이다. id 9950~9969 고정·{@code FOREIGN_KEY_CHECKS=0}(LT-02 try-finally).
 */
class ClaimActiveStatusConsistencyTest extends AbstractIntegrationTest {

    private static final long ID_BASE = 9950L;
    private static final long ID_LAST = 9969L;
    private static final long PRODUCT_ID = 9950L;
    private static final long VARIANT_ID = 9950L;
    private static final long DUMMY_FK_ID = 9950L;
    private static final long ITEM_PRICE = 10_000L;

    @Autowired
    private ClaimRepository claimRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        cleanup();
        seedOneGraphPerStatus();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("활성 집합: ClaimStatus.isActive()와 JPQL 가드 4곳이 모든 상태에서 같은 판정을 낸다")
    void enumAndJpqlAgreeOnEveryStatus() {
        for (ClaimStatus status : ClaimStatus.values()) {
            long id = idOf(status);
            boolean expectedActive = status.isActive();
            String because = "상태 " + status + " — enum isActive()=" + expectedActive
                    + " 와 JPQL 가드가 갈렸다. 활성 기준을 한쪽만 고쳤는지 확인할 것";

            assertThat(claimRepository.existsActiveByOrderItemId(id)).as(because + " (existsActiveByOrderItemId)")
                    .isEqualTo(expectedActive);
            assertThat(claimRepository.existsActiveByBuyerId(id)).as(because + " (existsActiveByBuyerId)")
                    .isEqualTo(expectedActive);
            assertThat(claimRepository.countActiveBySellerId(id)).as(because + " (countActiveBySellerId)")
                    .isEqualTo(expectedActive ? 1L : 0L);
            assertThat(claimRepository.findActiveByOrderItemIdIn(List.of(id))).as(because + " (findActiveByOrderItemIdIn)")
                    .hasSize(expectedActive ? 1 : 0);
        }
    }

    @Test
    @DisplayName("활성 집합: 현재 활성으로 판정되는 상태는 REQUESTED·APPROVED 둘뿐이다")
    void activeStatusesAreRequestedAndApprovedOnly() {
        List<ClaimStatus> active = Arrays.stream(ClaimStatus.values())
                .filter(ClaimStatus::isActive)
                .toList();

        // 기준을 의도적으로 바꿀 때는 이 줄과 JPQL 4곳을 함께 고친다(둘 중 하나만 고치면 위 테스트가 먼저 깨진다).
        assertThat(active).containsExactly(ClaimStatus.REQUESTED, ClaimStatus.APPROVED);
    }

    // 모든 시드 INSERT는 ? positional 바인딩 + 정적 SQL이다(문자열 concat 없음·SQL injection 위험 없음).

    /** 상태마다 구매자·셀러·주문·품목·클레임을 같은 id로 하나씩 만든다(상태 간 간섭 제거·id = ID_BASE + ordinal). */
    private void seedOneGraphPerStatus() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("INSERT INTO product (id, public_id, seller_id, category_id, name, status, base_price, "
                                + "created_at, updated_at) VALUES (?, ?, ?, ?, '활성집합상품', 'SALE', 10000, NOW(6), NOW(6))",
                        PRODUCT_ID, pid("prd_", "CASCPRD"), ID_BASE, DUMMY_FK_ID);
                jdbc.update("INSERT INTO product_variant (id, public_id, product_id, variant_code, additional_price, "
                                + "status, is_soldout_manual, display_order, option1_value_id, created_at, updated_at) "
                                + "VALUES (?, ?, ?, 'VCCASC', 0, 'SALE', 0, 1, ?, NOW(6), NOW(6))",
                        VARIANT_ID, pid("var_", "CASCVAR"), PRODUCT_ID, DUMMY_FK_ID);
                for (ClaimStatus claimStatus : ClaimStatus.values()) {
                    seedGraphFor(claimStatus);
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private void seedGraphFor(ClaimStatus claimStatus) {
        long id = idOf(claimStatus);
        String tag = "CASC" + id;
        jdbc.update("INSERT INTO `user` (id, public_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                id, pid("usr_", tag));
        jdbc.update("INSERT INTO seller (id, public_id, company_name, ceo_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, '활성집합셀러', '대표', 'ACTIVE', NOW(6), NOW(6))",
                id, pid("slr_", tag));
        jdbc.update("INSERT INTO `order` (id, public_id, buyer_id, order_no, status, total_price, "
                        + "discount_amount, shipping_fee, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PAID', ?, 0, 0, NOW(6), NOW(6))",
                id, pid("ord_", tag), id, "ORD" + tag, ITEM_PRICE);
        jdbc.update("INSERT INTO order_item (id, public_id, order_id, product_id, variant_id, seller_id, "
                        + "quantity, unit_price, total_price, item_status, created_at, updated_at, product_name, commission_rate) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 'PAID', NOW(6), NOW(6), '활성집합상품', 1000)",
                id, pid("oit_", tag), id, PRODUCT_ID, VARIANT_ID, id, ITEM_PRICE, ITEM_PRICE);
        jdbc.update("INSERT INTO claim (id, public_id, order_item_id, type, reason_code, status, requested_by, "
                        + "requested_at, previous_order_item_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CANCEL', 'BUYER_CHANGED_MIND', ?, ?, NOW(6), 'PAID', NOW(6), NOW(6))",
                id, pid("clm_", tag), id, claimStatus.name(), id);
    }

    private void cleanup() {
        tx.executeWithoutResult(status -> {
            try {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
                jdbc.update("DELETE FROM claim WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM order_item WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `order` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM product_variant WHERE id = ?", VARIANT_ID);
                jdbc.update("DELETE FROM product WHERE id = ?", PRODUCT_ID);
                jdbc.update("DELETE FROM seller WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
                jdbc.update("DELETE FROM `user` WHERE id BETWEEN ? AND ?", ID_BASE, ID_LAST);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static long idOf(ClaimStatus status) {
        return ID_BASE + status.ordinal();
    }

    private static String pid(String prefix, String tag) {
        return prefix + (tag + "00000000000000000000000000").substring(0, 26);
    }
}
