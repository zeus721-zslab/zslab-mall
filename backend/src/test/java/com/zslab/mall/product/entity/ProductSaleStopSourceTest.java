package com.zslab.mall.product.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.SaleStopSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판매중지 주체 불변식 도메인 검증(Track 96-5·D-206): STOPPED ↔ saleStopSource NOT NULL·주체 없는 STOPPED 전이 불가. */
class ProductSaleStopSourceTest {

    private static Product saleProduct() {
        Product product = Product.create(1L, 1L, "상품", null, 1000L, null);
        product.approve(); // PENDING → SALE
        return product;
    }

    @Test
    @DisplayName("stopSale(source): 주체 기록 · null 주체는 IllegalArgumentException(상태 불변) · 재판매 시 null 복귀")
    void stopSale_recordsSourceAndResumeClears() {
        Product product = saleProduct();
        assertThat(product.getSaleStopSource()).isNull();

        assertThatThrownBy(() -> product.stopSale(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SALE);
        assertThat(product.getSaleStopSource()).isNull();

        product.stopSale(SaleStopSource.SELLER);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.STOPPED);
        assertThat(product.getSaleStopSource()).isEqualTo(SaleStopSource.SELLER);

        product.resumeSale();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SALE);
        assertThat(product.getSaleStopSource()).isNull();

        product.stopSale(SaleStopSource.ADMIN);
        assertThat(product.getSaleStopSource()).isEqualTo(SaleStopSource.ADMIN);
    }

    @Test
    @DisplayName("escalateStopToAdmin(보정): STOPPED·SELLER → ADMIN(status 유지) / SALE·PENDING·STOPPED·ADMIN → IllegalStateException·불변")
    void escalateStopToAdmin_onlyFromSellerStop() {
        Product sellerStopped = saleProduct();
        sellerStopped.stopSale(SaleStopSource.SELLER);
        sellerStopped.escalateStopToAdmin();
        assertThat(sellerStopped.getStatus()).isEqualTo(ProductStatus.STOPPED);
        assertThat(sellerStopped.getSaleStopSource()).isEqualTo(SaleStopSource.ADMIN);
        assertThatThrownBy(sellerStopped::escalateStopToAdmin).isInstanceOf(IllegalStateException.class);
        assertThat(sellerStopped.getSaleStopSource()).isEqualTo(SaleStopSource.ADMIN);

        Product sale = saleProduct();
        assertThatThrownBy(sale::escalateStopToAdmin).isInstanceOf(IllegalStateException.class);
        assertThat(sale.getSaleStopSource()).isNull();
        Product pending = Product.create(1L, 1L, "상품", null, 1000L, null);
        assertThatThrownBy(pending::escalateStopToAdmin).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("전이 위반: PENDING·STOPPED에서 stopSale → IllegalStateException·주체 미기록 / SALE에서 resumeSale → IllegalStateException")
    void illegalTransitions_doNotRecordSource() {
        Product pending = Product.create(1L, 1L, "상품", null, 1000L, null);
        assertThatThrownBy(() -> pending.stopSale(SaleStopSource.ADMIN)).isInstanceOf(IllegalStateException.class);
        assertThat(pending.getSaleStopSource()).isNull();

        Product stopped = saleProduct();
        stopped.stopSale(SaleStopSource.ADMIN);
        assertThatThrownBy(() -> stopped.stopSale(SaleStopSource.SELLER)).isInstanceOf(IllegalStateException.class);
        assertThat(stopped.getSaleStopSource()).as("같은 상태 재요청은 주체를 덮어쓰지 않는다").isEqualTo(SaleStopSource.ADMIN);

        Product sale = saleProduct();
        assertThatThrownBy(sale::resumeSale).isInstanceOf(IllegalStateException.class);
    }
}
