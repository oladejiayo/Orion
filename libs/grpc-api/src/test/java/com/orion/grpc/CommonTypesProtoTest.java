package com.orion.grpc;

import com.orion.common.v1.AssetClass;
import com.orion.common.v1.CorrelationContext;
import com.orion.common.v1.Decimal;
import com.orion.common.v1.Money;
import com.orion.common.v1.Side;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.common.v1.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the generated common types from types.proto behave correctly:
 * construction, field access, optional fields, enums, and serialization round-trips.
 */
@DisplayName("Common Types Proto")
class CommonTypesProtoTest {

    @Nested
    @DisplayName("Timestamp")
    class TimestampTests {

        @Test
        @DisplayName("should build with seconds and nanos")
        void shouldBuildWithSecondsAndNanos() {
            var ts = Timestamp.newBuilder()
                    .setSeconds(1_700_000_000L)
                    .setNanos(123_456_789)
                    .build();

            assertThat(ts.getSeconds()).isEqualTo(1_700_000_000L);
            assertThat(ts.getNanos()).isEqualTo(123_456_789);
        }

        @Test
        @DisplayName("should default to zero for unset fields")
        void shouldDefaultToZero() {
            var ts = Timestamp.getDefaultInstance();
            assertThat(ts.getSeconds()).isZero();
            assertThat(ts.getNanos()).isZero();
        }

        @Test
        @DisplayName("should serialize and deserialize correctly")
        void shouldRoundTrip() throws Exception {
            var ts = Timestamp.newBuilder()
                    .setSeconds(1_234_567_890L)
                    .setNanos(999_999_999)
                    .build();

            byte[] bytes = ts.toByteArray();
            var parsed = Timestamp.parseFrom(bytes);

            assertThat(parsed).isEqualTo(ts);
            assertThat(parsed.getSeconds()).isEqualTo(1_234_567_890L);
            assertThat(parsed.getNanos()).isEqualTo(999_999_999);
        }
    }

    @Nested
    @DisplayName("Money")
    class MoneyTests {

        @Test
        @DisplayName("should preserve precision in string amount")
        void shouldPreservePrecision() {
            var money = Money.newBuilder()
                    .setAmount("123456789.123456789")
                    .setCurrency("USD")
                    .build();

            // String representation preserves arbitrary precision — no floating-point loss
            assertThat(money.getAmount()).isEqualTo("123456789.123456789");
            assertThat(money.getCurrency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("should serialize and deserialize correctly")
        void shouldRoundTrip() throws Exception {
            var money = Money.newBuilder()
                    .setAmount("99.875")
                    .setCurrency("EUR")
                    .build();

            var parsed = Money.parseFrom(money.toByteArray());
            assertThat(parsed).isEqualTo(money);
        }
    }

    @Nested
    @DisplayName("Decimal")
    class DecimalTests {

        @Test
        @DisplayName("should preserve arbitrary precision")
        void shouldPreservePrecision() {
            var dec = Decimal.newBuilder()
                    .setValue("0.000000001")
                    .build();

            assertThat(dec.getValue()).isEqualTo("0.000000001");
        }
    }

    @Nested
    @DisplayName("TenantContext")
    class TenantContextTests {

        @Test
        @DisplayName("should support required tenant_id")
        void shouldSupportTenantId() {
            var tc = TenantContext.newBuilder()
                    .setTenantId("acme-corp")
                    .build();

            assertThat(tc.getTenantId()).isEqualTo("acme-corp");
            // Optional field should report not-present when unset
            assertThat(tc.hasTenantName()).isFalse();
        }

        @Test
        @DisplayName("should support optional tenant_name")
        void shouldSupportOptionalTenantName() {
            var tc = TenantContext.newBuilder()
                    .setTenantId("acme-corp")
                    .setTenantName("Acme Corporation")
                    .build();

            assertThat(tc.hasTenantName()).isTrue();
            assertThat(tc.getTenantName()).isEqualTo("Acme Corporation");
        }
    }

    @Nested
    @DisplayName("UserContext")
    class UserContextTests {

        @Test
        @DisplayName("should build with required and optional fields")
        void shouldBuildWithAllFields() {
            var uc = UserContext.newBuilder()
                    .setUserId("trader-42")
                    .setUsername("jdoe")
                    .setEmail("jdoe@acme.com")
                    .build();

            assertThat(uc.getUserId()).isEqualTo("trader-42");
            assertThat(uc.getUsername()).isEqualTo("jdoe");
            assertThat(uc.hasEmail()).isTrue();
            assertThat(uc.getEmail()).isEqualTo("jdoe@acme.com");
        }

        @Test
        @DisplayName("should allow optional email to be absent")
        void shouldAllowAbsentEmail() {
            var uc = UserContext.newBuilder()
                    .setUserId("trader-1")
                    .setUsername("admin")
                    .build();

            assertThat(uc.hasEmail()).isFalse();
        }
    }

    @Nested
    @DisplayName("CorrelationContext")
    class CorrelationContextTests {

        @Test
        @DisplayName("should support correlation_id and optional causation_id")
        void shouldSupportCorrelationFields() {
            var cc = CorrelationContext.newBuilder()
                    .setCorrelationId("corr-abc-123")
                    .setCausationId("cause-xyz-789")
                    .build();

            assertThat(cc.getCorrelationId()).isEqualTo("corr-abc-123");
            assertThat(cc.hasCausationId()).isTrue();
            assertThat(cc.getCausationId()).isEqualTo("cause-xyz-789");
        }
    }

    @Nested
    @DisplayName("Side enum")
    class SideEnumTests {

        @Test
        @DisplayName("should have correct field numbers")
        void shouldHaveCorrectFieldNumbers() {
            assertThat(Side.SIDE_UNSPECIFIED.getNumber()).isZero();
            assertThat(Side.SIDE_BUY.getNumber()).isEqualTo(1);
            assertThat(Side.SIDE_SELL.getNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("should look up by number")
        void shouldLookUpByNumber() {
            assertThat(Side.forNumber(1)).isEqualTo(Side.SIDE_BUY);
            assertThat(Side.forNumber(2)).isEqualTo(Side.SIDE_SELL);
            assertThat(Side.forNumber(0)).isEqualTo(Side.SIDE_UNSPECIFIED);
        }
    }

    @Nested
    @DisplayName("AssetClass enum")
    class AssetClassEnumTests {

        @Test
        @DisplayName("should have all five asset classes plus UNSPECIFIED")
        void shouldHaveAllValues() {
            assertThat(AssetClass.values()).hasSize(
                    // 5 asset classes + UNSPECIFIED + UNRECOGNIZED (protobuf adds this)
                    7
            );
            assertThat(AssetClass.ASSET_CLASS_FX.getNumber()).isEqualTo(1);
            assertThat(AssetClass.ASSET_CLASS_RATES.getNumber()).isEqualTo(2);
            assertThat(AssetClass.ASSET_CLASS_CREDIT.getNumber()).isEqualTo(3);
            assertThat(AssetClass.ASSET_CLASS_EQUITIES.getNumber()).isEqualTo(4);
            assertThat(AssetClass.ASSET_CLASS_COMMODITIES.getNumber()).isEqualTo(5);
        }
    }
}
