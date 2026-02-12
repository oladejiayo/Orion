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

---

## 🔮 What's Coming Next

| Story | What It Will Add |
|---|---|
| US-01-02 | Docker Compose local environment — run databases, message brokers, etc. locally |
| US-01-03 | Shared event model library — common message formats for all services |
| US-01-04 | Shared security library — authentication and authorization building blocks |
| US-01-05 | Shared observability library — logging, metrics, and tracing |
| US-01-06 | Protobuf definitions — gRPC service contracts |
| US-01-07 | GitHub Actions CI — automated build and test on every push |

---

*Last updated after US-01-01*
