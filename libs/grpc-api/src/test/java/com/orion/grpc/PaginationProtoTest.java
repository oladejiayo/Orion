package com.orion.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.orion.common.v1.PaginationRequest;
import com.orion.common.v1.PaginationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated pagination messages from pagination.proto: offset-based pagination,
 * cursor-based pagination, and serialization.
 */
@DisplayName("Pagination Proto")
class PaginationProtoTest {

    @Nested
    @DisplayName("PaginationRequest")
    class RequestTests {

        @Test
        @DisplayName("should build offset-based request")
        void shouldBuildOffsetBased() {
            var req = PaginationRequest.newBuilder().setPage(3).setPageSize(25).build();

            assertThat(req.getPage()).isEqualTo(3);
            assertThat(req.getPageSize()).isEqualTo(25);
            assertThat(req.hasCursor()).isFalse();
        }

        @Test
        @DisplayName("should build cursor-based request")
        void shouldBuildCursorBased() {
            var req =
                    PaginationRequest.newBuilder()
                            .setPageSize(50)
                            .setCursor("eyJpZCI6MTAwfQ==")
                            .build();

            assertThat(req.hasCursor()).isTrue();
            assertThat(req.getCursor()).isEqualTo("eyJpZCI6MTAwfQ==");
        }

        @Test
        @DisplayName("should serialize and deserialize correctly")
        void shouldRoundTrip() throws Exception {
            var req = PaginationRequest.newBuilder().setPage(1).setPageSize(20).build();

            var parsed = PaginationRequest.parseFrom(req.toByteArray());
            assertThat(parsed).isEqualTo(req);
        }
    }

    @Nested
    @DisplayName("PaginationResponse")
    class ResponseTests {

        @Test
        @DisplayName("should build full response with all fields")
        void shouldBuildFullResponse() {
            var resp =
                    PaginationResponse.newBuilder()
                            .setPage(2)
                            .setPageSize(25)
                            .setTotalItems(150)
                            .setTotalPages(6)
                            .setHasNext(true)
                            .setNextCursor("eyJpZCI6NTB9")
                            .build();

            assertThat(resp.getPage()).isEqualTo(2);
            assertThat(resp.getPageSize()).isEqualTo(25);
            assertThat(resp.getTotalItems()).isEqualTo(150L);
            assertThat(resp.getTotalPages()).isEqualTo(6);
            assertThat(resp.getHasNext()).isTrue();
            assertThat(resp.hasNextCursor()).isTrue();
        }

        @Test
        @DisplayName("should indicate last page with has_next=false")
        void shouldIndicateLastPage() {
            var resp =
                    PaginationResponse.newBuilder()
                            .setPage(6)
                            .setPageSize(25)
                            .setTotalItems(150)
                            .setTotalPages(6)
                            .setHasNext(false)
                            .build();

            assertThat(resp.getHasNext()).isFalse();
            assertThat(resp.hasNextCursor()).isFalse();
        }
    }
}
