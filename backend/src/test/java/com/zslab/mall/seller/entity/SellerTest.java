package com.zslab.mall.seller.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.seller.enums.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Seller#changeStatus}·{@link Seller#update} 단위 검증(Track 89-D). 전이 가드는 {@link SellerStatus#canTransitionTo}에
 * 위임하므로 여기서는 합법 1건·불법(같은 상태·TERMINATED 이후·null) 시 IllegalStateException·상태 불변만 확인한다.
 */
class SellerTest {

    private static Seller seller(SellerStatus status) {
        return Seller.create("상호", "123-45-67890", "대표", "seller@example.com", "02-000-0000", status);
    }

    @Test
    @DisplayName("changeStatus: ACTIVE→SUSPENDED→ACTIVE→TERMINATED 순차 전이 성공·TERMINATED 이후는 전부 IllegalStateException")
    void changeStatus_legalChain_thenTerminatedBlocksAll() {
        Seller seller = seller(SellerStatus.ACTIVE);
        seller.changeStatus(SellerStatus.SUSPENDED);
        seller.changeStatus(SellerStatus.ACTIVE);
        seller.changeStatus(SellerStatus.TERMINATED);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.TERMINATED);

        for (SellerStatus next : SellerStatus.values()) {
            assertThatThrownBy(() -> seller.changeStatus(next)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.TERMINATED);
    }

    @Test
    @DisplayName("changeStatus: 같은 상태 재요청·PENDING→SUSPENDED·null → IllegalStateException·상태 불변")
    void changeStatus_illegal_keepsStatus() {
        Seller seller = seller(SellerStatus.PENDING);
        assertThatThrownBy(() -> seller.changeStatus(SellerStatus.PENDING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seller.changeStatus(SellerStatus.SUSPENDED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seller.changeStatus(null)).isInstanceOf(IllegalStateException.class);
        assertThat(seller.getStatus()).isEqualTo(SellerStatus.PENDING);
    }

    @Test
    @DisplayName("update: 6필드 반영·commissionRate null 환원 / 범위 밖(10001)·companyName 공백 → IllegalArgumentException")
    void update_appliesFields_andRejectsInvalid() {
        Seller seller = seller(SellerStatus.ACTIVE);
        seller.update("새상호", null, "새대표", null, null, 1500);
        assertThat(seller.getCompanyName()).isEqualTo("새상호");
        assertThat(seller.getBusinessNo()).isNull();
        assertThat(seller.getCeoName()).isEqualTo("새대표");
        assertThat(seller.getContactEmail()).isNull();
        assertThat(seller.getCommissionRate()).isEqualTo(1500);

        seller.update("새상호", null, "새대표", null, null, null);
        assertThat(seller.getCommissionRate()).isNull();

        assertThatThrownBy(() -> seller.update("새상호", null, "새대표", null, null, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> seller.update(" ", null, "새대표", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
