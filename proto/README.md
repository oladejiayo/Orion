# Protocol Buffer Definitions

gRPC service contracts shared between Orion microservices. Proto files are compiled into Java stubs during the Maven build.

## Structure

```
proto/
└── v1/
    ├── common.proto       # Shared types (Timestamp, Money, etc.)
    ├── marketdata.proto   # Market data streaming service
    ├── rfq.proto          # RFQ lifecycle service
    ├── order.proto        # Order management service
    └── trade.proto        # Trade query service
```

## Versioning

Proto packages follow `com.orion.<domain>.v1` convention. Breaking changes require a new `v2/` directory.
