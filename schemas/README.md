# Event Schemas

JSON Schema definitions for Kafka event envelopes. Used for runtime validation of events published to and consumed from the event bus.

## Structure

```
schemas/
└── v1/
    ├── marketdata.tick.json
    ├── rfq.created.json
    ├── rfq.quote_received.json
    ├── rfq.quote_accepted.json
    ├── trade.executed.json
    └── ...
```

## Convention

- Schemas follow JSON Schema draft-07
- File names match the event type in lowercase dot notation
- All schemas include the canonical event envelope fields
