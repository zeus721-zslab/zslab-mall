package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.common.auth.BuyerActorResolver;
import com.zslab.mall.common.observability.TracedEventPublisher;
import com.zslab.mall.notification.adapter.MockNotificationSender;
import com.zslab.mall.notification.adapter.MockSmsSender;
import com.zslab.mall.notification.adapter.NotificationSender;
import com.zslab.mall.notification.adapter.SmsSender;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.payment.controller.MockPaymentCallbackController;
import com.zslab.mall.payment.gateway.MockPaymentGateway;
import com.zslab.mall.payment.gateway.MockRefundAutoCallbackListener;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import com.zslab.mall.payment.service.MockPaymentCallbackService;
import com.zslab.mall.payment.service.PaymentService;
import com.zslab.mall.refund.repository.RefundRepository;
import com.zslab.mall.refund.scheduler.MockRefundPendingRecoveryScheduler;
import com.zslab.mall.refund.service.RefundRecoveryService;
import com.zslab.mall.refund.service.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Track 97 D-209 외부 서비스 구현체 선택 프로퍼티 검증. DB 없는 {@link ApplicationContextRunner}로 세 키
 * ({@code zslab.payment.gateway}·{@code zslab.notification.sms-sender}·{@code zslab.notification.email-sender})의
 * 미지정·{@code mock}·mock 외 값에서 Mock 빈 존재/부재를 단언한다. Mock PG 빈이 빠지면 {@code @ConditionalOnBean(MockPaymentGateway)}
 * 리스너·복구 스케줄러도 함께 빠져야 한다(자동 연동). 의존 빈은 Mockito mock이다.
 *
 * <p>사용자 구성 등록 순서는 {@code @ConditionalOnBean} 평가 순서와 같으므로 {@link MockPaymentGateway}를 리스너·스케줄러보다 앞에 둔다
 * (운영은 컴포넌트 스캔 순서 — 같은 패키지 {@code MockPaymentGateway} < {@code MockRefund*}·{@code payment} < {@code refund}).
 */
class ExternalServiceSelectionTest {

    private static final String PAYMENT_KEY = "zslab.payment.gateway";
    private static final String SMS_KEY = "zslab.notification.sms-sender";
    private static final String EMAIL_KEY = "zslab.notification.email-sender";

    @Configuration
    static class MockDependencies {
        @Bean
        TracedEventPublisher tracedEventPublisher() {
            return Mockito.mock(TracedEventPublisher.class);
        }

        @Bean
        RefundService refundService() {
            return Mockito.mock(RefundService.class);
        }

        @Bean
        RefundRepository refundRepository() {
            return Mockito.mock(RefundRepository.class);
        }

        @Bean
        RefundRecoveryService refundRecoveryService() {
            return Mockito.mock(RefundRecoveryService.class);
        }

        @Bean
        PaymentRepository paymentRepository() {
            return Mockito.mock(PaymentRepository.class);
        }

        @Bean
        OrderRepository orderRepository() {
            return Mockito.mock(OrderRepository.class);
        }

        @Bean
        PaymentService paymentService() {
            return Mockito.mock(PaymentService.class);
        }

        @Bean
        OrderService orderService() {
            return Mockito.mock(OrderService.class);
        }

        @Bean
        BuyerActorResolver buyerActorResolver() {
            return Mockito.mock(BuyerActorResolver.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockDependencies.class,
                    MockPaymentGateway.class, MockRefundAutoCallbackListener.class, MockRefundPendingRecoveryScheduler.class,
                    MockPaymentCallbackService.class, MockPaymentCallbackController.class,
                    MockSmsSender.class, MockNotificationSender.class);

    @Test
    @DisplayName("키 미지정(기본값): Mock PG·자동 콜백 리스너·복구 스케줄러·mock 콜백 서비스/컨트롤러·Mock SMS·Mock 이메일 빈 존재")
    void keysMissing_allMockBeansPresent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PaymentGateway.class);
            assertThat(context).hasSingleBean(MockPaymentGateway.class);
            assertThat(context).hasSingleBean(MockRefundAutoCallbackListener.class);
            assertThat(context).hasSingleBean(MockRefundPendingRecoveryScheduler.class);
            assertThat(context).hasSingleBean(MockPaymentCallbackService.class);
            assertThat(context).hasSingleBean(MockPaymentCallbackController.class);
            assertThat(context).hasSingleBean(SmsSender.class);
            assertThat(context).hasSingleBean(MockSmsSender.class);
            assertThat(context).hasSingleBean(NotificationSender.class);
            assertThat(context).hasSingleBean(MockNotificationSender.class);
        });
    }

    @Test
    @DisplayName("세 키 모두 mock 명시: 기본값과 동일하게 Mock 빈 존재")
    void keysMock_allMockBeansPresent() {
        runner.withPropertyValues(PAYMENT_KEY + "=mock", SMS_KEY + "=mock", EMAIL_KEY + "=mock").run(context -> {
            assertThat(context).hasSingleBean(MockPaymentGateway.class);
            assertThat(context).hasSingleBean(MockRefundAutoCallbackListener.class);
            assertThat(context).hasSingleBean(MockRefundPendingRecoveryScheduler.class);
            assertThat(context).hasSingleBean(MockPaymentCallbackController.class);
            assertThat(context).hasSingleBean(MockSmsSender.class);
            assertThat(context).hasSingleBean(MockNotificationSender.class);
        });
    }

    @Test
    @DisplayName("PG 키가 mock 외 값: Mock PG·리스너·스케줄러·mock 콜백 서비스/컨트롤러 부재, SMS·이메일 Mock은 유지")
    void paymentReal_mockPaymentBeansAbsent() {
        runner.withPropertyValues(PAYMENT_KEY + "=some-pg").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaymentGateway.class);
            assertThat(context).doesNotHaveBean(MockPaymentGateway.class);
            assertThat(context).doesNotHaveBean(MockRefundAutoCallbackListener.class);
            assertThat(context).doesNotHaveBean(MockRefundPendingRecoveryScheduler.class);
            assertThat(context).doesNotHaveBean(MockPaymentCallbackService.class);
            assertThat(context).doesNotHaveBean(MockPaymentCallbackController.class);
            assertThat(context).hasSingleBean(MockSmsSender.class);
            assertThat(context).hasSingleBean(MockNotificationSender.class);
        });
    }

    @Test
    @DisplayName("SMS 키가 mock 외 값: Mock SMS 부재, PG·이메일 Mock은 유지")
    void smsReal_mockSmsAbsent() {
        runner.withPropertyValues(SMS_KEY + "=some-sms").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SmsSender.class);
            assertThat(context).hasSingleBean(MockPaymentGateway.class);
            assertThat(context).hasSingleBean(MockNotificationSender.class);
        });
    }

    @Test
    @DisplayName("이메일 키가 mock 외 값: Mock 이메일 부재, PG·SMS Mock은 유지")
    void emailReal_mockEmailAbsent() {
        runner.withPropertyValues(EMAIL_KEY + "=smtp").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationSender.class);
            assertThat(context).hasSingleBean(MockPaymentGateway.class);
            assertThat(context).hasSingleBean(MockSmsSender.class);
        });
    }
}
