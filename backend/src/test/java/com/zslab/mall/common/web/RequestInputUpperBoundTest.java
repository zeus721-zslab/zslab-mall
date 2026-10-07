package com.zslab.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.inventory.controller.request.AdminInventoryAdjustRequest;
import com.zslab.mall.inventory.controller.request.SellerInventoryMarkInboundRequest;
import com.zslab.mall.inventory.controller.request.SellerInventoryMarkOutboundRequest;
import com.zslab.mall.order.controller.request.OrderItemRequest;
import com.zslab.mall.order.controller.request.ShippingAddressRequest;
import com.zslab.mall.product.controller.request.AdminProductCreateRequest;
import com.zslab.mall.product.controller.request.AdminProductUpdateRequest;
import com.zslab.mall.product.controller.request.AdminProductVariantsRequest;
import com.zslab.mall.product.controller.request.ProductRegistrationRequest;
import com.zslab.mall.product.controller.request.ProductVariantRequest;
import com.zslab.mall.product.controller.request.SellerProductUpdateRequest;
import com.zslab.mall.product.controller.request.SellerProductVariantsRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 입력 상한(SEC-21·SEC-22·P-10) 경계값. 요청 DTO 필드마다 상한값은 통과, 상한 + 1(음수 증감은 하한 − 1)은 위반인지 Bean Validation으로 확인한다.
 * HTTP 400 VALIDATION_FAILED 매핑은 주문·재고 조정 통합 테스트가 대표로 확인한다.
 */
class RequestInputUpperBoundTest {

    private static final long MAX_PRICE = 1_000_000_000L;
    private static final int MAX_STOCK_PER_REQUEST = 1_000_000;
    private static final int MAX_ORDER_QUANTITY = 999;

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of(AdminProductCreateRequest.class, "basePrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(AdminProductUpdateRequest.class, "basePrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(ProductRegistrationRequest.class, "basePrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(SellerProductUpdateRequest.class, "basePrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(AdminProductVariantsRequest.Item.class, "additionalPrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(ProductVariantRequest.class, "additionalPrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(SellerProductVariantsRequest.Item.class, "additionalPrice", MAX_PRICE, MAX_PRICE + 1),
                Arguments.of(AdminProductVariantsRequest.Item.class, "initialStock", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(ProductVariantRequest.class, "initialStock", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(SellerProductVariantsRequest.Item.class, "initialStock", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(AdminInventoryAdjustRequest.class, "quantityDelta", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(AdminInventoryAdjustRequest.class, "quantityDelta", -MAX_STOCK_PER_REQUEST, -MAX_STOCK_PER_REQUEST - 1),
                Arguments.of(SellerInventoryMarkInboundRequest.class, "quantity", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(SellerInventoryMarkOutboundRequest.class, "quantity", MAX_STOCK_PER_REQUEST, MAX_STOCK_PER_REQUEST + 1),
                Arguments.of(OrderItemRequest.class, "quantity", MAX_ORDER_QUANTITY, MAX_ORDER_QUANTITY + 1));
    }

    @ParameterizedTest(name = "{0}.{1}: {2} 통과 · {3} 위반")
    @MethodSource("boundaries")
    void boundary(Class<?> requestType, String property, Number allowed, Number exceeded) {
        assertThat(validator.validateValue(requestType, property, allowed)).as("경계값은 통과").isEmpty();
        assertThat(validator.validateValue(requestType, property, exceeded)).as("경계 밖은 위반").isNotEmpty();
    }

    // SEC-06(D-268): 배송지 문자열 상한 = order_shipping_snapshot 컬럼 길이(deliveryMemo는 TEXT라 정책 상한 500).
    static Stream<Arguments> lengthBoundaries() {
        return Stream.of(
                Arguments.of(ShippingAddressRequest.class, "recipientName", 50),
                Arguments.of(ShippingAddressRequest.class, "recipientPhone", 20),
                Arguments.of(ShippingAddressRequest.class, "zonecode", 10),
                Arguments.of(ShippingAddressRequest.class, "addressRoad", 200),
                Arguments.of(ShippingAddressRequest.class, "addressJibun", 200),
                Arguments.of(ShippingAddressRequest.class, "addressDetail", 200),
                Arguments.of(ShippingAddressRequest.class, "deliveryMemo", 500));
    }

    @ParameterizedTest(name = "{0}.{1}: {2}자 통과 · {2}+1자 위반")
    @MethodSource("lengthBoundaries")
    void lengthBoundary(Class<?> requestType, String property, int maxLength) {
        assertThat(validator.validateValue(requestType, property, "가".repeat(maxLength))).as("경계값은 통과").isEmpty();
        assertThat(validator.validateValue(requestType, property, "가".repeat(maxLength + 1))).as("경계 밖은 위반").isNotEmpty();
    }
}
