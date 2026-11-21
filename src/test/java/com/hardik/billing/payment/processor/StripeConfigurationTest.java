package com.hardik.billing.payment.processor;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeConfigurationTest {

    @Test
    void simulatedModeStartsWithoutStripeConfigurationAndTheWebhookReportsMissingSecret() {
        context(Map.of()).run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(PaymentProcessor.class);
            assertThat(app.getBean(PaymentProcessor.class)).isInstanceOf(SimulatedProcessor.class);
            assertThatThrownBy(() -> app.getBean(StripeGateway.class).verifyWebhook("{}", "invalid"))
                    .isInstanceOfSatisfying(BillingException.class,
                            error -> assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        });
    }

    @Test
    void stripeModeRequiresAnEnvironmentKeyAtStartup() {
        context(Map.of()).withPropertyValues("billing.processor=stripe").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage(
                    "Stripe mode requires STRIPE_API_KEY with an sk_test_ prefix.");
        });
    }

    @Test
    void stripeModeRejectsAnInvalidKeyWithoutPrintingItsValue() {
        context(Map.of("STRIPE_API_KEY", "invalid-key-value"))
                .withPropertyValues("billing.processor=stripe").run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).hasRootCauseMessage(
                            "Stripe mode requires STRIPE_API_KEY with an sk_test_ prefix.");
                    assertThat(app.getStartupFailure()).hasStackTraceContaining("STRIPE_API_KEY");
                    assertThat(app.getStartupFailure()).hasMessageNotContaining("invalid-key-value");
                });
    }

    private static ApplicationContextRunner context(Map<String, Object> environment) {
        return new ApplicationContextRunner()
                .withUserConfiguration(StripeGateway.class, StripeProcessor.class, SimulatedProcessor.class)
                .withInitializer(app -> {
                    String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
                    app.getEnvironment().getPropertySources()
                            .replace(name, new SystemEnvironmentPropertySource(name, environment));
                });
    }
}
