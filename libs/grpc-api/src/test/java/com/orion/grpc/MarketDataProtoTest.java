package com.orion.grpc;

import com.orion.common.v1.CorrelationContext;
import com.orion.common.v1.Decimal;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.marketdata.v1.DataQuality;
import com.orion.marketdata.v1.HistoricalTicksRequest;
import com.orion.marketdata.v1.HistoricalTicksResponse;
import com.orion.marketdata.v1.MarketDataServiceGrpc;
import com.orion.marketdata.v1.MarketSnapshot;
import com.orion.marketdata.v1.MarketTick;
import com.orion.marketdata.v1.OrderBookDepth;
import com.orion.marketdata.v1.PriceLevel;
import com.orion.marketdata.v1.SnapshotRequest;
import com.orion.marketdata.v1.SnapshotResponse;
import com.orion.marketdata.v1.TickSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the generated Market Data service proto messages and service descriptor.
 * Tests cover snapshot requests, streaming subscriptions, market ticks, and order book depth.
 */
@DisplayName("Market Data Proto")
class MarketDataProtoTest {

    @Nested
    @DisplayName("Service descriptor")
    class ServiceDescriptorTests {

        @Test
        @DisplayName("should have correct service name")
        void shouldHaveCorrectServiceName() {
            var descriptor = MarketDataServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getName()).isEqualTo("orion.marketdata.v1.MarketDataService");
        }

        @Test
        @DisplayName("should define three RPCs: GetSnapshot, StreamTicks, GetHistoricalTicks")
        void shouldDefineThreeRpcs() {
            var descriptor = MarketDataServiceGrpc.getServiceDescriptor();
            // Each service method is registered in the descriptor
            assertThat(descriptor.getMethods()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("SnapshotRequest")
    class SnapshotRequestTests {

        @Test
        @DisplayName("should build with instrument IDs and depth options")
        void shouldBuildWithInstruments() {
            var req = SnapshotRequest.newBuilder()
                    .addInstrumentIds("EUR/USD")
                    .addInstrumentIds("GBP/USD")
                    .addInstrumentIds("USD/JPY")
                    .setIncludeDepth(true)
                    .setDepthLevels(10)
                    .build();

            assertThat(req.getInstrumentIdsList()).containsExactly("EUR/USD", "GBP/USD", "USD/JPY");
            assertThat(req.getIncludeDepth()).isTrue();
            assertThat(req.hasDepthLevels()).isTrue();
            assertThat(req.getDepthLevels()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("MarketSnapshot")
    class MarketSnapshotTests {

        @Test
        @DisplayName("should build full snapshot with order book depth")
        void shouldBuildWithDepth() {
            var snapshot = MarketSnapshot.newBuilder()
                    .setInstrumentId("EUR/USD")
                    .setBid(decimal("1.0850"))
                    .setAsk(decimal("1.0852"))
                    .setMid(decimal("1.0851"))
                    .setSpread(decimal("0.0002"))
                    .setLastUpdate(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setDepth(OrderBookDepth.newBuilder()
                            .addBids(PriceLevel.newBuilder()
                                    .setPrice(decimal("1.0850"))
                                    .setQuantity(decimal("5000000"))
                                    .setOrderCount(3))
                            .addAsks(PriceLevel.newBuilder()
                                    .setPrice(decimal("1.0852"))
                                    .setQuantity(decimal("3000000"))
                                    .setOrderCount(2)))
                    .setQuality(DataQuality.newBuilder()
                            .setIsStale(false)
                            .setIsIndicative(false)
                            .setSource("reuters"))
                    .build();

            assertThat(snapshot.getInstrumentId()).isEqualTo("EUR/USD");
            assertThat(snapshot.hasDepth()).isTrue();
            assertThat(snapshot.getDepth().getBidsList()).hasSize(1);
            assertThat(snapshot.getDepth().getAsksList()).hasSize(1);
            assertThat(snapshot.getQuality().getIsStale()).isFalse();
            assertThat(snapshot.getQuality().getSource()).isEqualTo("reuters");
        }
    }

    @Nested
    @DisplayName("SnapshotResponse")
    class SnapshotResponseTests {

        @Test
        @DisplayName("should contain map of instrument snapshots")
        void shouldContainSnapshotMap() {
            var response = SnapshotResponse.newBuilder()
                    .putSnapshots("EUR/USD", MarketSnapshot.newBuilder()
                            .setInstrumentId("EUR/USD")
                            .setBid(decimal("1.0850"))
                            .setAsk(decimal("1.0852"))
                            .build())
                    .putSnapshots("GBP/USD", MarketSnapshot.newBuilder()
                            .setInstrumentId("GBP/USD")
                            .setBid(decimal("1.2600"))
                            .setAsk(decimal("1.2603"))
                            .build())
                    .setTimestamp(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .build();

            assertThat(response.getSnapshotsMap()).hasSize(2);
            assertThat(response.getSnapshotsMap()).containsKey("EUR/USD");
            assertThat(response.getSnapshotsMap()).containsKey("GBP/USD");
        }
    }

    @Nested
    @DisplayName("MarketTick")
    class MarketTickTests {

        @Test
        @DisplayName("should build tick with all fields")
        void shouldBuildTick() {
            var tick = MarketTick.newBuilder()
                    .setInstrumentId("EUR/USD")
                    .setBid(decimal("1.0850"))
                    .setAsk(decimal("1.0852"))
                    .setMid(decimal("1.0851"))
                    .setTimestamp(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setSource("reuters")
                    .setSequence(42L)
                    .setQuality(DataQuality.newBuilder().setIsStale(false))
                    .build();

            assertThat(tick.getSource()).isEqualTo("reuters");
            assertThat(tick.getSequence()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should serialize and deserialize correctly")
        void shouldRoundTrip() throws Exception {
            var tick = MarketTick.newBuilder()
                    .setInstrumentId("USD/JPY")
                    .setBid(decimal("149.50"))
                    .setAsk(decimal("149.53"))
                    .setMid(decimal("149.515"))
                    .setTimestamp(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(500_000))
                    .setSource("bloomberg")
                    .setSequence(100L)
                    .build();

            var parsed = MarketTick.parseFrom(tick.toByteArray());
            assertThat(parsed).isEqualTo(tick);
        }
    }

    @Nested
    @DisplayName("TickSubscription")
    class TickSubscriptionTests {

        @Test
        @DisplayName("should build subscription with context")
        void shouldBuildWithContext() {
            var sub = TickSubscription.newBuilder()
                    .addInstrumentIds("EUR/USD")
                    .addInstrumentIds("GBP/USD")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setCorrelation(CorrelationContext.newBuilder().setCorrelationId("corr-123"))
                    .build();

            assertThat(sub.getInstrumentIdsList()).containsExactly("EUR/USD", "GBP/USD");
            assertThat(sub.getTenant().getTenantId()).isEqualTo("acme-corp");
        }
    }

    @Nested
    @DisplayName("HistoricalTicks")
    class HistoricalTicksTests {

        @Test
        @DisplayName("should build request with time range")
        void shouldBuildRequest() {
            var req = HistoricalTicksRequest.newBuilder()
                    .setInstrumentId("EUR/USD")
                    .setStartTime(Timestamp.newBuilder().setSeconds(1_699_990_000L))
                    .setEndTime(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setMaxResults(500)
                    .build();

            assertThat(req.getInstrumentId()).isEqualTo("EUR/USD");
            assertThat(req.hasMaxResults()).isTrue();
            assertThat(req.getMaxResults()).isEqualTo(500);
        }

        @Test
        @DisplayName("should build response with ticks and has_more flag")
        void shouldBuildResponse() {
            var resp = HistoricalTicksResponse.newBuilder()
                    .addTicks(MarketTick.newBuilder()
                            .setInstrumentId("EUR/USD")
                            .setBid(decimal("1.0850"))
                            .setAsk(decimal("1.0852"))
                            .build())
                    .setHasMore(true)
                    .build();

            assertThat(resp.getTicksList()).hasSize(1);
            assertThat(resp.getHasMore()).isTrue();
        }
    }

    // --- Helper ---

    private static Decimal.Builder decimal(String value) {
        return Decimal.newBuilder().setValue(value);
    }
}
