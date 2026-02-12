package com.orion.grpc;

import com.orion.admin.v1.AdminServiceGrpc;
import com.orion.admin.v1.CreateInstrumentRequest;
import com.orion.admin.v1.GetInstrumentRequest;
import com.orion.admin.v1.InstrumentDetails;
import com.orion.admin.v1.InstrumentStatus;
import com.orion.admin.v1.ListInstrumentsRequest;
import com.orion.admin.v1.ListInstrumentsResponse;
import com.orion.admin.v1.SetKillSwitchRequest;
import com.orion.admin.v1.SetKillSwitchResponse;
import com.orion.admin.v1.UpdateInstrumentRequest;
import com.orion.admin.v1.UpdateLimitsRequest;
import com.orion.admin.v1.UpdateLimitsResponse;
import com.orion.common.v1.AssetClass;
import com.orion.common.v1.Decimal;
import com.orion.common.v1.PaginationRequest;
import com.orion.common.v1.PaginationResponse;
import com.orion.common.v1.TenantContext;
import com.orion.common.v1.Timestamp;
import com.orion.common.v1.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the generated Admin service proto messages — instrument management,
 * kill switch, trading limits, and service descriptor.
 */
@DisplayName("Admin Proto")
class AdminProtoTest {

    @Nested
    @DisplayName("Service descriptor")
    class ServiceDescriptorTests {

        @Test
        @DisplayName("should have correct service name")
        void shouldHaveCorrectServiceName() {
            var descriptor = AdminServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getName()).isEqualTo("orion.admin.v1.AdminService");
        }

        @Test
        @DisplayName("should define six RPCs")
        void shouldDefineSixRpcs() {
            // CreateInstrument, UpdateInstrument, GetInstrument, ListInstruments, SetKillSwitch, UpdateLimits
            var descriptor = AdminServiceGrpc.getServiceDescriptor();
            assertThat(descriptor.getMethods()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("InstrumentStatus enum")
    class InstrumentStatusTests {

        @Test
        @DisplayName("should have all instrument statuses")
        void shouldHaveAllStatuses() {
            assertThat(InstrumentStatus.INSTRUMENT_STATUS_UNSPECIFIED.getNumber()).isZero();
            assertThat(InstrumentStatus.INSTRUMENT_STATUS_ACTIVE.getNumber()).isEqualTo(1);
            assertThat(InstrumentStatus.INSTRUMENT_STATUS_SUSPENDED.getNumber()).isEqualTo(2);
            assertThat(InstrumentStatus.INSTRUMENT_STATUS_DELISTED.getNumber()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("InstrumentDetails")
    class InstrumentDetailsTests {

        @Test
        @DisplayName("should build full instrument with all fields")
        void shouldBuildFull() {
            var instrument = InstrumentDetails.newBuilder()
                    .setInstrumentId("inst-eurusd")
                    .setSymbol("EUR/USD")
                    .setName("Euro vs US Dollar")
                    .setAssetClass(AssetClass.ASSET_CLASS_FX)
                    .setStatus(InstrumentStatus.INSTRUMENT_STATUS_ACTIVE)
                    .setPricePrecision(5)
                    .setMinQuantity(Decimal.newBuilder().setValue("100000"))
                    .setMaxQuantity(Decimal.newBuilder().setValue("50000000"))
                    .setTickSize(Decimal.newBuilder().setValue("0.00001"))
                    .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .build();

            assertThat(instrument.getSymbol()).isEqualTo("EUR/USD");
            assertThat(instrument.getAssetClass()).isEqualTo(AssetClass.ASSET_CLASS_FX);
            assertThat(instrument.hasPricePrecision()).isTrue();
            assertThat(instrument.getPricePrecision()).isEqualTo(5);
            assertThat(instrument.hasUpdatedAt()).isFalse();
        }

        @Test
        @DisplayName("should support minimal instrument without optional fields")
        void shouldSupportMinimal() {
            var instrument = InstrumentDetails.newBuilder()
                    .setInstrumentId("inst-us10y")
                    .setSymbol("US10Y")
                    .setName("US 10-Year Treasury")
                    .setAssetClass(AssetClass.ASSET_CLASS_RATES)
                    .setStatus(InstrumentStatus.INSTRUMENT_STATUS_ACTIVE)
                    .build();

            assertThat(instrument.hasPricePrecision()).isFalse();
            assertThat(instrument.hasMinQuantity()).isFalse();
            assertThat(instrument.hasTickSize()).isFalse();
        }
    }

    @Nested
    @DisplayName("CreateInstrument")
    class CreateInstrumentTests {

        @Test
        @DisplayName("should build create request with context")
        void shouldBuildCreateRequest() {
            var req = CreateInstrumentRequest.newBuilder()
                    .setSymbol("GBP/USD")
                    .setName("British Pound vs US Dollar")
                    .setAssetClass(AssetClass.ASSET_CLASS_FX)
                    .setPricePrecision(5)
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("admin-1").setUsername("admin"))
                    .build();

            assertThat(req.getSymbol()).isEqualTo("GBP/USD");
            assertThat(req.getAssetClass()).isEqualTo(AssetClass.ASSET_CLASS_FX);
        }
    }

    @Nested
    @DisplayName("UpdateInstrument")
    class UpdateInstrumentTests {

        @Test
        @DisplayName("should build partial update request")
        void shouldBuildPartialUpdate() {
            // Only update status — other fields remain unchanged
            var req = UpdateInstrumentRequest.newBuilder()
                    .setInstrumentId("inst-eurusd")
                    .setStatus(InstrumentStatus.INSTRUMENT_STATUS_SUSPENDED)
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("admin-1").setUsername("admin"))
                    .build();

            assertThat(req.hasStatus()).isTrue();
            assertThat(req.hasName()).isFalse();
            assertThat(req.hasPricePrecision()).isFalse();
        }
    }

    @Nested
    @DisplayName("KillSwitch")
    class KillSwitchTests {

        @Test
        @DisplayName("should build kill switch activation request")
        void shouldBuildActivation() {
            var req = SetKillSwitchRequest.newBuilder()
                    .setEnabled(true)
                    .setReason("Flash crash detected — halting all trading")
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("admin-1").setUsername("admin"))
                    .build();

            assertThat(req.getEnabled()).isTrue();
            assertThat(req.hasReason()).isTrue();
        }

        @Test
        @DisplayName("should build kill switch response")
        void shouldBuildResponse() {
            var resp = SetKillSwitchResponse.newBuilder()
                    .setSuccess(true)
                    .setKillSwitchActive(true)
                    .setToggledAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .build();

            assertThat(resp.getSuccess()).isTrue();
            assertThat(resp.getKillSwitchActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("UpdateLimits")
    class UpdateLimitsTests {

        @Test
        @DisplayName("should build limits update with partial fields")
        void shouldBuildPartialLimitsUpdate() {
            var req = UpdateLimitsRequest.newBuilder()
                    .setUserId("trader-42")
                    .setMaxNotional(Decimal.newBuilder().setValue("50000000"))
                    .setMaxOpenOrders(100)
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setUser(UserContext.newBuilder().setUserId("admin-1").setUsername("admin"))
                    .build();

            assertThat(req.getUserId()).isEqualTo("trader-42");
            assertThat(req.hasMaxNotional()).isTrue();
            assertThat(req.hasMaxOpenOrders()).isTrue();
            assertThat(req.hasMaxRequestsPerSecond()).isFalse();
        }

        @Test
        @DisplayName("should build update response")
        void shouldBuildResponse() {
            var resp = UpdateLimitsResponse.newBuilder()
                    .setSuccess(true)
                    .setUserId("trader-42")
                    .build();

            assertThat(resp.getSuccess()).isTrue();
            assertThat(resp.hasErrorMessage()).isFalse();
        }
    }

    @Nested
    @DisplayName("ListInstruments")
    class ListInstrumentsTests {

        @Test
        @DisplayName("should build filtered list request")
        void shouldBuildFilteredRequest() {
            var req = ListInstrumentsRequest.newBuilder()
                    .setTenant(TenantContext.newBuilder().setTenantId("acme-corp"))
                    .setPagination(PaginationRequest.newBuilder().setPage(1).setPageSize(50))
                    .setAssetClass(AssetClass.ASSET_CLASS_FX)
                    .setStatus(InstrumentStatus.INSTRUMENT_STATUS_ACTIVE)
                    .build();

            assertThat(req.hasAssetClass()).isTrue();
            assertThat(req.hasStatus()).isTrue();
        }
    }

    @Nested
    @DisplayName("Serialization")
    class SerializationTests {

        @Test
        @DisplayName("should round-trip InstrumentDetails")
        void shouldRoundTrip() throws Exception {
            var instrument = InstrumentDetails.newBuilder()
                    .setInstrumentId("inst-eurusd")
                    .setSymbol("EUR/USD")
                    .setName("Euro vs US Dollar")
                    .setAssetClass(AssetClass.ASSET_CLASS_FX)
                    .setStatus(InstrumentStatus.INSTRUMENT_STATUS_ACTIVE)
                    .setPricePrecision(5)
                    .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                    .setUpdatedAt(Timestamp.newBuilder().setSeconds(1_700_050_000L))
                    .build();

            var parsed = InstrumentDetails.parseFrom(instrument.toByteArray());
            assertThat(parsed).isEqualTo(instrument);
            assertThat(parsed.hasUpdatedAt()).isTrue();
        }
    }
}
