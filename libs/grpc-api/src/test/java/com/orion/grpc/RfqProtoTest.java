package com.orion.grpc;

import com.orion.common.v1.CorrelationContext;
import com.orion.common.v1.Decimal;
import com.orion.common.v1.PaginationRequest;
import com.orion.common.v1.Side;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.common.v1.UserContext;
import com.orion.rfq.v1.AcceptQuoteRequest;
import com.orion.rfq.v1.AcceptQuoteResponse;
import com.orion.rfq.v1.CancelRFQRequest;
import com.orion.rfq.v1.CancelRFQResponse;
import com.orion.rfq.v1.CreateRFQRequest;
import com.orion.rfq.v1.CreateRFQResponse;
import com.orion.rfq.v1.GetRFQRequest;
import com.orion.rfq.v1.ListRFQsRequest;
import com.orion.rfq.v1.ListRFQsResponse;
import com.orion.rfq.v1.Quote;
import com.orion.rfq.v1.QuoteAccepted;
import com.orion.rfq.v1.RFQCancelled;
import com.orion.rfq.v1.RFQDetails;
import com.orion.rfq.v1.RFQExpired;
import com.orion.rfq.v1.RFQServiceGrpc;
import com.orion.rfq.v1.RFQStatus;
import com.orion.rfq.v1.RFQUpdate;
import com.orion.rfq.v1.WatchRFQRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the generated RFQ service proto messages, enums, oneof semantics,
 * and service descriptor.
 */
@DisplayName("RFQ Proto")
class RfqProtoTest {

    @Nested
    @DisplayName("Service descriptor")
    class ServiceDescriptorTests {

        @Test
        @DisplayName("should have correct service name")
        void shouldHaveCorrectServiceName() {
            var descriptor = RFQServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getName()).isEqualTo("orion.rfq.v1.RFQService");
        }

        @Test
        @DisplayName("should define six RPCs")
        void shouldDefineSixRpcs() {
            // CreateRFQ, GetRFQ, ListRFQs, AcceptQuote, CancelRFQ, WatchRFQ
            var descriptor = RFQServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getMethods()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("RFQStatus enum")
    class RfqStatusTests {

        @Test
        @DisplayName("should have all lifecycle statuses")
        void shouldHaveAllStatuses() {
            assertThat(RFQStatus.RFQ_STATUS_UNSPECIFIED.getNumber()).isZero();
            assertThat(RFQStatus.RFQ_STATUS_CREATED.getNumber()).isEqualTo(1);
            assertThat(RFQStatus.RFQ_STATUS_SENT.getNumber()).isEqualTo(2);
            assertThat(RFQStatus.RFQ_STATUS_QUOTING.getNumber()).isEqualTo(3);
            assertThat(RFQStatus.RFQ_STATUS_ACCEPTED.getNumber()).isEqualTo(4);
            assertThat(RFQStatus.RFQ_STATUS_REJECTED.getNumber()).isEqualTo(5);
            assertThat(RFQStatus.RFQ_STATUS_EXPIRED.getNumber()).isEqualTo(6);
            assertThat(RFQStatus.RFQ_STATUS_CANCELLED.getNumber()).isEqualTo(7);
            assertThat(RFQStatus.RFQ_STATUS_TRADED.getNumber()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("CreateRFQ")
    class CreateRfqTests {

        @Test
        @DisplayName("should build full create request")
        void shouldBuildCreateRequest() {
            var req = CreateRFQRequest.newBuilder()
                    .setInstrumentId("EUR/USD")
                    .setSide(Side.SIDE_BUY)
                    .setQuantity(Decimal.newBuilder().setValue("1000000"))
                    .setExpirySeconds(30)
                    .setAllowPartial(true)
                    .setMinQuantity(Decimal.newBuilder().setValue("500000"))
                    .setIdempotencyKey("idem-abc-123")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("trader-42").setUsername("jdoe"))
                    .setCorrelation(CorrelationContext.newBuilder().setCorrelationId("corr-123"))
                    .build();

            assertThat(req.getInstrumentId()).isEqualTo("EUR/USD");
            assertThat(req.getSide()).isEqualTo(Side.SIDE_BUY);
            assertThat(req.getExpirySeconds()).isEqualTo(30);
            assertThat(req.getAllowPartial()).isTrue();
            assertThat(req.hasMinQuantity()).isTrue();
        }

        @Test
        @DisplayName("should build create response")
        void shouldBuildCreateResponse() {
            var resp = CreateRFQResponse.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_CREATED)
                    .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setExpiresAt(Timestamp.newBuilder().setSeconds(1_700_000_030L))
                    .build();

            assertThat(resp.getRfqId()).isEqualTo("rfq-001");
            assertThat(resp.getStatus()).isEqualTo(RFQStatus.RFQ_STATUS_CREATED);
        }
    }

    @Nested
    @DisplayName("RFQDetails and Quote")
    class RfqDetailsTests {

        @Test
        @DisplayName("should build details with quotes")
        void shouldBuildWithQuotes() {
            var details = RFQDetails.newBuilder()
                    .setRfqId("rfq-001")
                    .setInstrumentId("EUR/USD")
                    .setSide(Side.SIDE_BUY)
                    .setQuantity(Decimal.newBuilder().setValue("1000000"))
                    .setStatus(RFQStatus.RFQ_STATUS_QUOTING)
                    .addQuotes(Quote.newBuilder()
                            .setQuoteId("q-1")
                            .setRfqId("rfq-001")
                            .setLpId("lp-citi")
                            .setLpName("Citibank")
                            .setPrice(Decimal.newBuilder().setValue("1.0850"))
                            .setQuantity(Decimal.newBuilder().setValue("1000000"))
                            .setIsBest(true))
                    .addQuotes(Quote.newBuilder()
                            .setQuoteId("q-2")
                            .setRfqId("rfq-001")
                            .setLpId("lp-jpmorgan")
                            .setLpName("JPMorgan")
                            .setPrice(Decimal.newBuilder().setValue("1.0851"))
                            .setQuantity(Decimal.newBuilder().setValue("1000000"))
                            .setIsBest(false))
                    .setRequesterUserId("trader-42")
                    .build();

            assertThat(details.getQuotesList()).hasSize(2);
            assertThat(details.getQuotes(0).getIsBest()).isTrue();
            assertThat(details.hasAcceptedQuote()).isFalse();
            assertThat(details.hasTradeId()).isFalse();
        }

        @Test
        @DisplayName("should support accepted quote and trade linkage")
        void shouldSupportAcceptedQuote() {
            var details = RFQDetails.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_TRADED)
                    .setAcceptedQuote(Quote.newBuilder()
                            .setQuoteId("q-1")
                            .setLpId("lp-citi"))
                    .setTradeId("trade-001")
                    .build();

            assertThat(details.hasAcceptedQuote()).isTrue();
            assertThat(details.hasTradeId()).isTrue();
            assertThat(details.getTradeId()).isEqualTo("trade-001");
        }
    }

    @Nested
    @DisplayName("ListRFQs")
    class ListRfqsTests {

        @Test
        @DisplayName("should build filtered list request with pagination")
        void shouldBuildFilteredRequest() {
            var req = ListRFQsRequest.newBuilder()
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setPagination(PaginationRequest.newBuilder().setPage(1).setPageSize(25))
                    .setInstrumentId("EUR/USD")
                    .addStatuses(RFQStatus.RFQ_STATUS_QUOTING)
                    .addStatuses(RFQStatus.RFQ_STATUS_CREATED)
                    .build();

            assertThat(req.hasInstrumentId()).isTrue();
            assertThat(req.getStatusesList()).containsExactly(
                    RFQStatus.RFQ_STATUS_QUOTING, RFQStatus.RFQ_STATUS_CREATED);
        }
    }

    @Nested
    @DisplayName("AcceptQuote")
    class AcceptQuoteTests {

        @Test
        @DisplayName("should build accept request and response")
        void shouldBuildAcceptFlow() {
            var req = AcceptQuoteRequest.newBuilder()
                    .setRfqId("rfq-001")
                    .setQuoteId("q-1")
                    .setIdempotencyKey("accept-idem-123")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("trader-42").setUsername("jdoe"))
                    .setCorrelation(CorrelationContext.newBuilder().setCorrelationId("corr-456"))
                    .build();

            var resp = AcceptQuoteResponse.newBuilder()
                    .setSuccess(true)
                    .setNewStatus(RFQStatus.RFQ_STATUS_TRADED)
                    .setTradeId("trade-001")
                    .build();

            assertThat(req.getIdempotencyKey()).isEqualTo("accept-idem-123");
            assertThat(resp.getSuccess()).isTrue();
            assertThat(resp.hasTradeId()).isTrue();
        }
    }

    @Nested
    @DisplayName("CancelRFQ")
    class CancelRfqTests {

        @Test
        @DisplayName("should build cancel request with optional reason")
        void shouldBuildCancelRequest() {
            var req = CancelRFQRequest.newBuilder()
                    .setRfqId("rfq-001")
                    .setReason("Changed mind")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("trader-42").setUsername("jdoe"))
                    .build();

            assertThat(req.hasReason()).isTrue();
            assertThat(req.getReason()).isEqualTo("Changed mind");

            var resp = CancelRFQResponse.newBuilder()
                    .setSuccess(true)
                    .setNewStatus(RFQStatus.RFQ_STATUS_CANCELLED)
                    .build();

            assertThat(resp.getNewStatus()).isEqualTo(RFQStatus.RFQ_STATUS_CANCELLED);
        }
    }

    @Nested
    @DisplayName("RFQUpdate oneof")
    class RfqUpdateTests {

        @Test
        @DisplayName("should carry new_quote update via oneof")
        void shouldCarryNewQuote() {
            var update = RFQUpdate.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_QUOTING)
                    .setNewQuote(Quote.newBuilder()
                            .setQuoteId("q-3")
                            .setLpId("lp-ubs")
                            .setLpName("UBS"))
                    .build();

            assertThat(update.getUpdateCase()).isEqualTo(RFQUpdate.UpdateCase.NEW_QUOTE);
            assertThat(update.getNewQuote().getLpName()).isEqualTo("UBS");
        }

        @Test
        @DisplayName("should carry expired update via oneof")
        void shouldCarryExpiredUpdate() {
            var update = RFQUpdate.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_EXPIRED)
                    .setExpired(RFQExpired.newBuilder()
                            .setExpiredAt(Timestamp.newBuilder().setSeconds(1_700_000_030L)))
                    .build();

            assertThat(update.getUpdateCase()).isEqualTo(RFQUpdate.UpdateCase.EXPIRED);
        }

        @Test
        @DisplayName("should carry accepted update via oneof")
        void shouldCarryAcceptedUpdate() {
            var update = RFQUpdate.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_TRADED)
                    .setAccepted(QuoteAccepted.newBuilder()
                            .setQuoteId("q-1")
                            .setTradeId("trade-001"))
                    .build();

            assertThat(update.getUpdateCase()).isEqualTo(RFQUpdate.UpdateCase.ACCEPTED);
            assertThat(update.getAccepted().getTradeId()).isEqualTo("trade-001");
        }

        @Test
        @DisplayName("should carry cancelled update via oneof")
        void shouldCarryCancelledUpdate() {
            var update = RFQUpdate.newBuilder()
                    .setRfqId("rfq-001")
                    .setStatus(RFQStatus.RFQ_STATUS_CANCELLED)
                    .setCancelled(RFQCancelled.newBuilder()
                            .setReason("Market closed"))
                    .build();

            assertThat(update.getUpdateCase()).isEqualTo(RFQUpdate.UpdateCase.CANCELLED);
            assertThat(update.getCancelled().getReason()).isEqualTo("Market closed");
        }
    }

    @Nested
    @DisplayName("Serialization")
    class SerializationTests {

        @Test
        @DisplayName("should round-trip CreateRFQRequest")
        void shouldRoundTrip() throws Exception {
            var req = CreateRFQRequest.newBuilder()
                    .setInstrumentId("EUR/USD")
                    .setSide(Side.SIDE_BUY)
                    .setQuantity(Decimal.newBuilder().setValue("1000000"))
                    .setExpirySeconds(30)
                    .setIdempotencyKey("idem-test")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .build();

            var parsed = CreateRFQRequest.parseFrom(req.toByteArray());
            assertThat(parsed).isEqualTo(req);
        }
    }
}
