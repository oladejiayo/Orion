package com.orion.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.orion.common.v1.CorrelationContext;
import com.orion.common.v1.Decimal;
import com.orion.common.v1.PaginationRequest;
import com.orion.common.v1.PaginationResponse;
import com.orion.common.v1.Side;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.execution.v1.ExecutionServiceGrpc;
import com.orion.execution.v1.ListTradesRequest;
import com.orion.execution.v1.ListTradesResponse;
import com.orion.execution.v1.TradeDetails;
import com.orion.execution.v1.TradeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated Execution service proto messages, trade status enum, and service
 * descriptor.
 */
@DisplayName("Execution Proto")
class ExecutionProtoTest {

    @Nested
    @DisplayName("Service descriptor")
    class ServiceDescriptorTests {

        @Test
        @DisplayName("should have correct service name")
        void shouldHaveCorrectServiceName() {
            var descriptor = ExecutionServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getName()).isEqualTo("orion.execution.v1.ExecutionService");
        }

        @Test
        @DisplayName("should define two RPCs: GetTrade, ListTrades")
        void shouldDefineTwoRpcs() {
            var descriptor = ExecutionServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getMethods()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("TradeStatus enum")
    class TradeStatusTests {

        @Test
        @DisplayName("should have all lifecycle statuses")
        void shouldHaveAllStatuses() {
            assertThat(TradeStatus.TRADE_STATUS_UNSPECIFIED.getNumber()).isZero();
            assertThat(TradeStatus.TRADE_STATUS_PENDING.getNumber()).isEqualTo(1);
            assertThat(TradeStatus.TRADE_STATUS_EXECUTED.getNumber()).isEqualTo(2);
            assertThat(TradeStatus.TRADE_STATUS_PARTIALLY_FILLED.getNumber()).isEqualTo(3);
            assertThat(TradeStatus.TRADE_STATUS_CANCELLED.getNumber()).isEqualTo(4);
            assertThat(TradeStatus.TRADE_STATUS_REJECTED.getNumber()).isEqualTo(5);
            assertThat(TradeStatus.TRADE_STATUS_SETTLED.getNumber()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("TradeDetails")
    class TradeDetailsTests {

        @Test
        @DisplayName("should build full trade with all fields")
        void shouldBuildFullTrade() {
            var trade =
                    TradeDetails.newBuilder()
                            .setTradeId("trade-001")
                            .setInstrumentId("EUR/USD")
                            .setSide(Side.SIDE_BUY)
                            .setPrice(Decimal.newBuilder().setValue("1.0850"))
                            .setQuantity(Decimal.newBuilder().setValue("1000000"))
                            .setNotional(Decimal.newBuilder().setValue("1085000"))
                            .setStatus(TradeStatus.TRADE_STATUS_EXECUTED)
                            .setRfqId("rfq-001")
                            .setCounterpartyId("lp-citi")
                            .setCounterpartyName("Citibank")
                            .setTraderUserId("trader-42")
                            .setExecutedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                            .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                            .setCorrelation(
                                    CorrelationContext.newBuilder().setCorrelationId("corr-789"))
                            .build();

            assertThat(trade.getTradeId()).isEqualTo("trade-001");
            assertThat(trade.getSide()).isEqualTo(Side.SIDE_BUY);
            assertThat(trade.hasRfqId()).isTrue();
            assertThat(trade.getRfqId()).isEqualTo("rfq-001");
            assertThat(trade.hasSettledAt()).isFalse();
        }

        @Test
        @DisplayName("should allow rfq_id to be absent for non-RFQ trades")
        void shouldAllowAbsentRfqId() {
            var trade =
                    TradeDetails.newBuilder()
                            .setTradeId("trade-002")
                            .setInstrumentId("US10Y")
                            .setStatus(TradeStatus.TRADE_STATUS_EXECUTED)
                            .build();

            // rfq_id is optional — not present for CLOB-originated trades
            assertThat(trade.hasRfqId()).isFalse();
        }
    }

    @Nested
    @DisplayName("ListTrades")
    class ListTradesTests {

        @Test
        @DisplayName("should build filtered list request")
        void shouldBuildFilteredRequest() {
            var req =
                    ListTradesRequest.newBuilder()
                            .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                            .setPagination(
                                    PaginationRequest.newBuilder().setPage(1).setPageSize(50))
                            .setInstrumentId("EUR/USD")
                            .addStatuses(TradeStatus.TRADE_STATUS_EXECUTED)
                            .addStatuses(TradeStatus.TRADE_STATUS_SETTLED)
                            .setSide(Side.SIDE_BUY)
                            .build();

            assertThat(req.hasInstrumentId()).isTrue();
            assertThat(req.getStatusesList()).hasSize(2);
            assertThat(req.hasSide()).isTrue();
        }

        @Test
        @DisplayName("should build list response with pagination")
        void shouldBuildListResponse() {
            var resp =
                    ListTradesResponse.newBuilder()
                            .addTrades(
                                    TradeDetails.newBuilder()
                                            .setTradeId("trade-001")
                                            .setStatus(TradeStatus.TRADE_STATUS_EXECUTED))
                            .setPagination(
                                    PaginationResponse.newBuilder()
                                            .setPage(1)
                                            .setPageSize(50)
                                            .setTotalItems(1)
                                            .setTotalPages(1))
                            .build();

            assertThat(resp.getTradesList()).hasSize(1);
            assertThat(resp.getPagination().getTotalItems()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Serialization")
    class SerializationTests {

        @Test
        @DisplayName("should round-trip TradeDetails")
        void shouldRoundTrip() throws Exception {
            var trade =
                    TradeDetails.newBuilder()
                            .setTradeId("trade-001")
                            .setInstrumentId("GBP/USD")
                            .setSide(Side.SIDE_SELL)
                            .setPrice(Decimal.newBuilder().setValue("1.2600"))
                            .setQuantity(Decimal.newBuilder().setValue("500000"))
                            .setStatus(TradeStatus.TRADE_STATUS_SETTLED)
                            .setSettledAt(Timestamp.newBuilder().setSeconds(1_700_100_000L))
                            .build();

            var parsed = TradeDetails.parseFrom(trade.toByteArray());
            assertThat(parsed).isEqualTo(trade);
            assertThat(parsed.hasSettledAt()).isTrue();
        }
    }
}
