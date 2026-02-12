# 🧒 Orion Platform — ELI5 Guide

> **ELI5** = "Explain Like I'm 5." This guide describes what we've built in the simplest possible terms. No jargon, just plain English.

---

## 🏠 What is Orion?

Imagine a **huge trading floor** where banks, hedge funds, and investors all want to buy and sell bonds. Right now, they do a lot of this with phone calls, chat messages, and spreadsheets — it's slow and messy.

**Orion** is a computer system that automates all of that. It's like a **super-fast post office** specifically for bond trades: someone asks for a price, someone else sends a price back, they agree, and the trade happens — all electronically in milliseconds.

---

## 📚 What We've Built (Implementation Log)

### ✅ US-01-01: Initialize Maven Multi-Module Monorepo

**📅 Implemented:** 2025-07-12  
**📁 Location:** Root directory (`/`)

#### What Did We Build?

We created the **empty building** — the organized folder structure where all of Orion's code will live.

#### Why Do We Need This?

Think of it like building a house. Before you move in any furniture (code), you need to lay the foundation, put up walls, and label every room. That's exactly what a "monorepo" is — **one big, well-organized house** for all our code.

Without this structure, developers would be throwing code into random folders like tossing clothes on the floor. With it, everyone knows exactly where to find things.

#### The Parts We Created

| File / Folder | What It Is | Simple Explanation |
|---|---|---|
| `pom.xml` | Parent build file | The "master blueprint" that tells Maven how to build everything |
| `mvnw` / `mvnw.cmd` | Maven wrapper scripts | A "batteries-included" button so you don't need to install Maven separately |
| `.gitignore` | Git ignore rules | A list of files Git should pretend don't exist (like temporary files) |
| `.editorconfig` | Editor settings | Rules so everyone's code editor uses the same spacing and formatting |
| `services/` | Microservices folder | Where each backend service (like RFQ, pricing) will live |
| `libs/` | Shared libraries folder | Reusable code that multiple services share (like a toolbox) |
| `proto/` | Protocol Buffers folder | Definitions for how services talk to each other (like a dictionary) |
| `schemas/` | Event schemas folder | Templates for the messages services send to each other |
| `web/` | Frontend folder | Where the React UI code will live |
| `infra/` | Infrastructure folder | Docker and Terraform configs to run everything |
| `docs/` | Documentation folder | Guides, architecture docs, and this very file! |
| `benchmarks/` | Performance tests folder | Where we'll put speed tests |
| `scripts/` | Utility scripts folder | Helper scripts for common tasks |
| `build-tools/verification/` | Structure tests | Automated tests that make sure this layout stays correct |

#### How It Works (The Flow)

```
Developer clones repo
        │
        ▼
 ┌──────────────────┐
 │  ./mvnw verify   │  ◄── Run this one command
 └────────┬─────────┘
          │
          ▼
 ┌──────────────────┐
 │  Maven reads     │
 │  pom.xml         │  ◄── Finds the blueprint
 └────────┬─────────┘
          │
          ▼
 ┌──────────────────┐
 │  Builds each     │
 │  module in order  │  ◄── Right now: just verification tests
 └────────┬─────────┘
          │
          ▼
 ┌──────────────────┐
 │  48 tests run    │
 │  All ✅ pass      │  ◄── Confirms the structure is correct
 └──────────────────┘
```

#### Key Concepts

| Concept | Simple Explanation |
|---|---|
| **Monorepo** | "Mono" = one. One single repository holding all the code for every service, library, and tool. Like keeping all your toys in one big toy chest instead of scattered around the house. |
| **Maven** | A build tool for Java. It reads `pom.xml` files and knows how to compile code, run tests, and package apps. Think of it as a robot chef that follows recipes. |
| **Multi-module** | The monorepo is split into modules (services, libs, etc.). Each module has its own `pom.xml` but they all share settings from the parent. Like rooms in a house — separate but connected. |
| **POM** | "Project Object Model" — an XML file (`pom.xml`) that describes a project: what it depends on, how to build it, what plugins to use. The recipe for the robot chef. |
| **TDD** | "Test-Driven Development" — we write the test *first* (saying what we expect), then write code to make the test pass. Like writing the exam questions before studying, so you know exactly what to learn. |
| **Maven Wrapper** | The `mvnw` script. It downloads the exact Maven version the project needs so everyone uses the same one. Like shipping a tool with its own batteries. |
| **Spring Boot** | A framework that makes it easy to build Java web applications. It handles a ton of boilerplate so developers can focus on business logic. Like a pre-built house frame — just add walls and furniture. |

---

## 📖 Glossary

| Term | Meaning |
|---|---|
| **Monorepo** | A single Git repository containing all project code |
| **Maven** | Java build automation tool |
| **POM** | Project Object Model — Maven's configuration file |
| **Module** | A sub-project within the Maven monorepo |
| **TDD** | Test-Driven Development — tests written before code |
| **CI/CD** | Continuous Integration / Continuous Delivery — automated build + deploy |
| **gRPC** | A fast protocol for services to communicate |
| **Protobuf** | Protocol Buffers — a compact data format used by gRPC |
| **Docker** | A tool that runs apps in isolated containers |
| **Docker Compose** | Starts multiple Docker containers from one YAML recipe |
| **Redpanda** | A lightweight Kafka-compatible message broker |
| **PostgreSQL** | A relational database for permanent data storage |
| **Redis** | An in-memory cache for super-fast data access |
| **Health Check** | A periodic ping to verify a service is running |
| **Named Volume** | Docker storage that persists after containers stop |

---

### ✅ US-01-02: Docker Compose Local Development Environment

**📅 Implemented:** 2025-07-12  
**📁 Location:** `infra/docker-compose/`

#### What Did We Build?

We set up a **one-command playground** that spins up all the infrastructure our services need (a database, a message bus, and a cache) right on your laptop.

#### Why Do We Need This?

Imagine you're building a restaurant (our trading platform). Before you cook anything, you need a kitchen — stoves, fridges, running water. In software:

- **PostgreSQL** = the fridge (stores data permanently)
- **Redpanda/Kafka** = the order ticket system (passes messages between cooks)
- **Redis** = the countertop (fast temporary storage for things you need right now)

Without Docker Compose, every developer would have to install and configure these tools by hand. Docker Compose gives everyone the **exact same kitchen** with one command.

#### The Parts We Created

| File | What It Is | Simple Explanation |
|------|-----------|-------------------|
| `docker-compose.yml` | Infrastructure recipe | One file that describes all 6 services and how they connect |
| `.env.example` | Config template | A list of settings (usernames, passwords) you can customize |
| `init-scripts/postgres/01-init-databases.sql` | Database setup | Creates separate databases for each microservice on first start |
| `scripts/start-local-env.sh` | Start script (Linux/Mac) | Starts everything and waits until it's healthy |
| `scripts/start-local-env.ps1` | Start script (Windows) | Same thing, but for PowerShell |
| `scripts/reset-local-env.sh` | Reset script (Linux/Mac) | Wipes all data and starts fresh |
| `scripts/reset-local-env.ps1` | Reset script (Windows) | Same thing, but for PowerShell |

#### How It Works (The Flow)

```
Developer runs: docker compose up -d
         │
         ▼
┌────────────────────────────────────────────────────┐
│            Docker creates orion-network             │
│                                                    │
│  ┌────────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ Redpanda   │  │ Postgres │  │    Redis       │  │
│  │ (messages) │  │ (storage)│  │   (cache)      │  │
│  │ :19092     │  │  :5432   │  │   :6379        │  │
│  └─────┬──────┘  └────┬─────┘  └──────┬────────┘  │
│        │              │               │            │
│  ┌─────┴──────┐  ┌────┴─────┐  ┌─────┴────────┐   │
│  │ Console    │  │ pgAdmin  │  │ Redis Cmdr   │   │
│  │ (view msgs)│  │ (view DB)│  │ (view cache) │   │
│  │  :8080     │  │  :5050   │  │   :8081      │   │
│  └────────────┘  └──────────┘  └──────────────┘   │
└────────────────────────────────────────────────────┘
         │
         ▼
  All services healthy ✅
  Open http://localhost:8080 to see Kafka UI
  Open http://localhost:5050 to manage databases
  Open http://localhost:8081 to inspect cache
```

#### Key Concepts

| Concept | Simple Explanation |
|---------|-------------------|
| **Docker** | A tool that runs apps in isolated boxes called "containers." Each container is like a mini computer with just one program installed. |
| **Docker Compose** | A tool that starts MULTIPLE Docker containers at once from a recipe file (`docker-compose.yml`). |
| **Redpanda** | A Kafka-compatible message broker — a post office that passes messages between services. Lighter than Apache Kafka. |
| **PostgreSQL** | A relational database — a giant spreadsheet that stores data permanently. |
| **Redis** | An in-memory cache — a super-fast sticky note board. Data is fast to read but can be lost on restart. |
| **Named Volume** | Docker's way of saving data outside a container. Even if the container is deleted, the data stays. |
| **Health Check** | A periodic "are you okay?" ping that Docker sends to each service to make sure it's running. |
| **.env file** | A file with secret settings (passwords, etc.) that is NOT saved in Git — each developer has their own copy. |

---

### ✅ US-01-03: Setup Shared Event Model Library

**📅 Implemented:** 2025-07-12  
**📁 Location:** `libs/event-model/`

#### What Did We Build?

We created a **shared vocabulary** for all services to communicate — like agreeing that every letter sent through the post office must use the same envelope format.

#### Why Do We Need This?

Imagine 10 different cooks in our restaurant kitchen (microservices). Each cook needs to pass notes to the others: "Order received!", "Food is ready!", "Table 5 cancelled!". If every cook invents their own note format, chaos ensues.

The **Event Model** is a set of rules that says: "Every note MUST have these fields: who wrote it, when, what kind of note it is, and the actual message." Now every cook speaks the same language.

In software terms, every event flowing through our Redpanda message bus uses the same **EventEnvelope** format — a standard wrapper around every message.

#### The Parts We Created

| File | What It Is | Simple Explanation |
|------|-----------|-------------------|
| `EventEnvelope.java` | The standard envelope | Every event is wrapped in this — like a postal envelope with sender, date, tracking number |
| `EventEntity.java` | Entity tracking | Identifies WHAT thing the event is about (e.g., "Trade #123, version 1") |
| `EventType.java` | Event type registry | A list of all 30 known event types (TradeExecuted, RFQCreated, etc.) |
| `EntityType.java` | Entity type list | A list of all 10 entity types (Trade, RFQ, Order, Quote, etc.) |
| `EventFactory.java` | Event creator | Helper methods to create events with auto-generated IDs and timestamps |
| `EventSerializer.java` | JSON converter | Converts events to/from JSON text (for sending over the network) |
| `EventValidator.java` | Event checker | Checks that an event has all required fields before sending it |
| `ValidationResult.java` | Check result | The result of validation — either "all good" or a list of problems |

#### How It Works (The Flow)

```
Service wants to announce: "Trade #123 executed!"
         │
         ▼
┌──────────────────────────────────────────────┐
│         EventFactory.create(...)             │
│  Auto-generates:                             │
│    • UUID event ID                           │
│    • Current timestamp (ISO 8601)            │
│    • Correlation ID (for tracing)            │
│    • Version = 1                             │
└─────────────────┬────────────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────┐
│       EventEnvelope<TradePayload>            │
│  ┌─────────────────────────────────────┐     │
│  │  eventId:       "abc-123-def"       │     │
│  │  eventType:     "TradeExecuted"     │     │
│  │  eventVersion:  1                   │     │
│  │  occurredAt:    "2025-07-12T..."    │     │
│  │  producer:      "execution-service" │     │
│  │  tenantId:      "tenant-001"        │     │
│  │  correlationId: "xyz-789"           │     │
│  │  entity: Trade / trade-123 / seq 1  │     │
│  │  payload:  { price: 99.5, qty: 100} │     │
│  └─────────────────────────────────────┘     │
└─────────────────┬────────────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────┐
│     EventValidator.validate(envelope)        │
│  Checks all required fields are present      │
│  → ValidationResult: valid ✅                │
└─────────────────┬────────────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────┐
│     EventSerializer.serialize(envelope)      │
│  → JSON string sent to Redpanda              │
└──────────────────────────────────────────────┘
```

#### Key Concepts

| Concept | Simple Explanation |
|---------|-------------------|
| **Event Envelope** | A standard wrapper around every message. Like a postal envelope — it has "from," "to," "date," and the letter inside. |
| **Java Record** | A special Java class that's **immutable** (can't be changed after creation). Perfect for events — once something happened, you can't un-happen it. |
| **Generic Type `<T>`** | The `EventEnvelope<T>` can hold ANY payload type. `T` is a placeholder — it could be a trade, a quote, an order, etc. Like an envelope that fits any letter. |
| **UUID** | "Universally Unique Identifier" — a random string like `550e8400-e29b-41d4-a716-446655440000`. So unique that two computers generating UUIDs at the same time will never collide. |
| **Correlation ID** | A tracking number that follows a business flow across multiple events. If an RFQ leads to a quote, which leads to a trade — they all share the same correlation ID so you can trace the chain. |
| **Causation ID** | Points to the event that *caused* this one. The trade was *caused by* the quote acceptance. |
| **ISO 8601** | A standard date format: `2025-07-12T15:30:00Z`. The "Z" means UTC. Used worldwide so there's no timezone confusion. |
| **Jackson** | A Java library that converts objects to/from JSON. Like a translator between Java objects and the text format used on the network. |
| **Immutability** | Once an event is created, it can never be modified. This is critical for audit trails — you can always prove exactly what happened and when. |

---

## 🔮 What's Coming Next

| Story | What It Will Add |
|---|---|
| US-01-04 | Shared security library — authentication and authorization building blocks |
| US-01-05 | Shared observability library — logging, metrics, and tracing |
| US-01-06 | Protobuf definitions — gRPC service contracts |
| US-01-07 | GitHub Actions CI — automated build and test on every push |

---

*Last updated after US-01-03*
