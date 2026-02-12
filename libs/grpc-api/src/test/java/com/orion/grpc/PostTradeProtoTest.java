package com.orion.grpc;

import com.orion.common.v1.Money;
import com.orion.common.v1.PaginationRequest;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.posttrade.v1.ConfirmationDetails;
import com.orion.posttrade.v1.ConfirmationStatus;
import com.orion.posttrade.v1.GetConfirmationRequest;
import com.orion.posttrade.v1.GetSettlementStatusRequest;
import com.orion.posttrade.v1.ListConfirmationsRequest;
import com.orion.posttrade.v1.ListConfirmationsResponse;
import com.orion.posttrade.v1.PostTradeServiceGrpc;
import com.orion.posttrade.v1.SettlementDetails;
import com.orion.posttrade.v1.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the generated Post-Trade service proto messages — confirmations,
 * settlements, status enums, and service descriptor.
 */
@DisplayName("Post-Trade Proto")
class PostTradeProtoTest {

    @Nested
    @DisplayName("Service descriptor")
    class ServiceDescriptorTests {

        @Test
        @DisplayName("should have correct service name")
        void shouldHaveCorrectServiceName() {
            var descriptor = PostTradeServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getName()).isEqualTo("orion.posttrade.v1.PostTradeService");
        }

        @Test
        @DisplayName("should define three RPCs")
        void shouldDefineThreeRpcs() {
            // GetConfirmation, GetSettlementStatus, ListConfirmations
            var descriptor = PostTradeServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getMethods()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("ConfirmationStatus enum")
    class ConfirmationStatusTests {

        @Test
        @DisplayName("should have all confirmation statuses")
        void shouldHaveAllStatuses() {
            assertThat(ConfirmationStatus.CONFIRMATION_STATUS_UNSPECIFIED.getNumber()).isZero();
            assertThat(ConfirmationStatus.CONFIRMATION_STATUS_PENDING.getNumber()).isEqualTo(1);
            assertThat(ConfirmationStatus.CONFIRMATION_STATUS_SENT.getNumber()).isEqualTo(2);
            assertThat(ConfirmationStatus.CONFIRMATION_STATUS_AFFIRMED.getNumber()).isEqualTo(3);
            assertThat(ConfirmationStatus.CONFIRMATION_STATUS_DISPUTED.getNumber()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("SettlementStatus enum")
    class SettlementStatusTests {

        @Test
        @DisplayName("should have all settlement statuses")
        void shouldHaveAllStatuses() {
            assertThat(SettlementStatus.SETTLEMENT_STATUS_UNSPECIFIED.getNumber()).isZero();
            assertThat(SettlementStatus.SETTLEMENT_STATUS_PENDING.getNumber()).isEqualTo(1);
            assertThat(SettlementStatus.SETTLEMENT_STATUS_INSTRUCTED.getNumber()).isEqualTo(2);
            assertThat(SettlementStatus.SETTLEMENT_STATUS_MATCHED.getNumber()).isEqualTo(3);
            assertThat(SettlementStatus.SETTLEMENT_STATUS_SETTLED.getNumber()).isEqualTo(4);
            assertThat(SettlementStatus.SETTLEMENT_STATUS_FAILED.getNumber()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("ConfirmationDetails")
    class ConfirmationDetailsTests {

        @Test
        @DisplayName("should build pending confirmation")
        void shouldBuildPendingConfirmation() {
            var conf = ConfirmationDetails.newBuilder()
                    .setConfirmationId("conf-001")
                    .setTradeId("trade-001")
                    .setStatus(ConfirmationStatus.CONFIRMATION_STATUS_SENT)
                    .setSentAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setCounterpartyId("lp-citi")
                    .build();

            assertThat(conf.getConfirmationId()).isEqualTo("conf-001");
            assertThat(conf.getStatus()).isEqualTo(ConfirmationStatus.CONFIRMATION_STATUS_SENT);
            assertThat(conf.hasAffirmedAt()).isFalse();
            assertThat(conf.hasDocumentRef()).isFalse();
        }

        @Test
        @DisplayName("should build affirmed confirmation with optional fields")
        void shouldBuildAffirmedConfirmation() {
            var conf = ConfirmationDetails.newBuilder()
                    .setConfirmationId("conf-001")
                    .setTradeId("trade-001")
                    .setStatus(ConfirmationStatus.CONFIRMATION_STATUS_AFFIRMED)
                    .setSentAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setAffirmedAt(Timestamp.newBuilder().setSeconds(1_700_000_060L))
                    .setCounterpartyId("lp-citi")
                    .setDocumentRef("DOC-2026-001234")
                    .build();

            assertThat(conf.hasAffirmedAt()).isTrue();
            assertThat(conf.hasDocumentRef()).isTrue();
            assertThat(conf.getDocumentRef()).isEqualTo("DOC-2026-001234");
        }
    }

    @Nested
    @DisplayName("SettlementDetails")
    class SettlementDetailsTests {

        @Test
        @DisplayName("should build pending settlement")
        void shouldBuildPendingSettlement() {
            var settlement = SettlementDetails.newBuilder()
                    .setSettlementId("sett-001")
                    .setTradeId("trade-001")
                    .setStatus(SettlementStatus.SETTLEMENT_STATUS_INSTRUCTED)
                    .setSettlementDate(Timestamp.newBuilder().setSeconds(1_700_086_400L))
                    .setSettlementAmount(Money.newBuilder().setAmount("1085000.00").setCurrency("USD"))
                    .build();

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SETTLEMENT_STATUS_INSTRUCTED);
            assertThat(settlement.getSettlementAmount().getCurrency()).isEqualTo("USD");
            assertThat(settlement.hasSettledAt()).isFalse();
            assertThat(settlement.hasFailureReason()).isFalse();
        }

        @Test
        @DisplayName("should build failed settlement with reason")
        void shouldBuildFailedSettlement() {
            var settlement = SettlementDetails.newBuilder()
                    .setSettlementId("sett-002")
                    .setTradeId("trade-002")
                    .setStatus(SettlementStatus.SETTLEMENT_STATUS_FAILED)
                    .setSettlementDate(Timestamp.newBuilder().setSeconds(1_700_086_400L))
                    .setSettlementAmount(Money.newBuilder().setAmount("500000.00").setCurrency("EUR"))
                    .setFailureReason("Insufficient funds in settlement account")
                    .build();

            assertThat(settlement.hasFailureReason()).isTrue();
            assertThat(settlement.getFailureReason()).isEqualTo("Insufficient funds in settlement account");
        }
    }

    @Nested
    @DisplayName("ListConfirmations")
    class ListConfirmationsTests {

        @Test
        @DisplayName("should build filtered list request")
        void shouldBuildFilteredRequest() {
            var req = ListConfirmationsRequest.newBuilder()
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setPagination(PaginationRequest.newBuilder().setPage(1).setPageSize(20))
                    .setStatus(ConfirmationStatus.CONFIRMATION_STATUS_PENDING)
                    .build();

            assertThat(req.hasStatus()).isTrue();
            assertThat(req.getStatus()).isEqualTo(ConfirmationStatus.CONFIRMATION_STATUS_PENDING);
        }
    }

    @Nested
    @DisplayName("Serialization")
    class SerializationTests {

        @Test
        @DisplayName("should round-trip SettlementDetails")
        void shouldRoundTrip() throws Exception {
            var settlement = SettlementDetails.newBuilder()
                    .setSettlementId("sett-003")
                    .setTradeId("trade-003")
                    .setStatus(SettlementStatus.SETTLEMENT_STATUS_SETTLED)
                    .setSettlementDate(Timestamp.newBuilder().setSeconds(1_700_086_400L))
                    .setSettlementAmount(Money.newBuilder().setAmount("2500000.00").setCurrency("GBP"))
                    .setSettledAt(Timestamp.newBuilder().setSeconds(1_700_090_000L))
                    .build();

            var parsed = SettlementDetails.parseFrom(settlement.toByteArray());
            assertThat(parsed).isEqualTo(settlement);
            assertThat(parsed.hasSettledAt()).isTrue();
        }
    }
}
