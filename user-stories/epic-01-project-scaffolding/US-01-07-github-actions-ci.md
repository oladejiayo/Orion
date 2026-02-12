# User Story: US-01-07 - Configure GitHub Actions CI Pipeline

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-07 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Configure GitHub Actions CI Pipeline |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** development team member  
**I want** automated CI pipelines that build, test, lint, and validate all code changes  
**So that** code quality is maintained and issues are caught early before merging

## Description

This story establishes GitHub Actions workflows for continuous integration. The pipelines will handle building all services and libraries, running tests, enforcing code quality standards, validating proto definitions, and generating build artifacts. The CI should provide fast feedback and support the monorepo structure.

## Acceptance Criteria

### AC1: Pull Request Workflow
- [ ] Triggers on all PRs to `main` branch
- [ ] Runs affected builds only (Nx affected)
- [ ] Runs affected tests only
- [ ] Runs linting on affected code
- [ ] Validates proto definitions
- [ ] Posts status checks to PR

### AC2: Main Branch Workflow
- [ ] Triggers on push to `main`
- [ ] Builds all projects
- [ ] Runs all tests
- [ ] Generates test coverage reports
- [ ] Builds Docker images for services
- [ ] Pushes images to registry (if configured)

### AC3: Build Caching
- [ ] Caches npm dependencies
- [ ] Caches Nx computation cache
- [ ] Caches Docker layers
- [ ] Cache is restored across workflow runs

### AC4: Test Reporting
- [ ] Test results displayed in GitHub UI
- [ ] Coverage reports uploaded as artifacts
- [ ] Coverage summary in PR comments
- [ ] Failed tests clearly visible

### AC5: Proto Validation
- [ ] `buf lint` runs on proto changes
- [ ] `buf breaking` checks backward compatibility
- [ ] Proto validation failure blocks PR

### AC6: Security Scanning
- [ ] Dependency vulnerability scanning
- [ ] Secret scanning enabled
- [ ] Security alerts surface in PR

### AC7: Docker Build
- [ ] Multi-stage Dockerfile builds
- [ ] Images tagged with commit SHA
- [ ] Images tagged with branch name
- [ ] Latest tag on main branch

## Technical Details

### Workflow Files Structure

```
/.github/
├── workflows/
│   ├── ci-pr.yml           # PR validation
│   ├── ci-main.yml         # Main branch builds
│   ├── proto-validate.yml  # Proto validation
│   └── docker-build.yml    # Docker image builds
├── actions/
│   └── setup-node/
│       └── action.yml      # Reusable setup action
└── CODEOWNERS
```

### PR Workflow (`.github/workflows/ci-pr.yml`)
```yaml
name: PR Validation

on:
  pull_request:
    branches: [main]
    types: [opened, synchronize, reopened]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

env:
  NX_CLOUD_ACCESS_TOKEN: ${{ secrets.NX_CLOUD_ACCESS_TOKEN }}
  NODE_VERSION: '20'

jobs:
  setup:
    runs-on: ubuntu-latest
    outputs:
      affected: ${{ steps.affected.outputs.affected }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Get affected projects
        id: affected
        run: |
          AFFECTED=$(npx nx show projects --affected --base=origin/main)
          echo "affected=$AFFECTED" >> $GITHUB_OUTPUT

  lint:
    needs: setup
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'

      - name: Restore Nx cache
        uses: actions/cache@v4
        with:
          path: .nx/cache
          key: nx-${{ hashFiles('package-lock.json') }}-${{ github.sha }}
          restore-keys: |
            nx-${{ hashFiles('package-lock.json') }}-

      - name: Install dependencies
        run: npm ci

      - name: Run affected lint
        run: npx nx affected --target=lint --base=origin/main

  test:
    needs: setup
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'

      - name: Restore Nx cache
        uses: actions/cache@v4
        with:
          path: .nx/cache
          key: nx-${{ hashFiles('package-lock.json') }}-${{ github.sha }}
          restore-keys: |
            nx-${{ hashFiles('package-lock.json') }}-

      - name: Install dependencies
        run: npm ci

      - name: Run affected tests
        run: npx nx affected --target=test --base=origin/main --coverage

      - name: Upload coverage
        uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          fail_ci_if_error: false

  build:
    needs: setup
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'

      - name: Restore Nx cache
        uses: actions/cache@v4
        with:
          path: .nx/cache
          key: nx-${{ hashFiles('package-lock.json') }}-${{ github.sha }}
          restore-keys: |
            nx-${{ hashFiles('package-lock.json') }}-

      - name: Install dependencies
        run: npm ci

      - name: Run affected build
        run: npx nx affected --target=build --base=origin/main

  proto-validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Buf
        uses: bufbuild/buf-setup-action@v1
        with:
          version: '1.28.1'

      - name: Buf lint
        run: buf lint proto/

      - name: Buf breaking
        run: buf breaking proto/ --against 'https://github.com/${{ github.repository }}.git#branch=main,subdir=proto'
        continue-on-error: true  # Warning only for now
```

### Main Branch Workflow (`.github/workflows/ci-main.yml`)
```yaml
name: Main CI

on:
  push:
    branches: [main]

env:
  NX_CLOUD_ACCESS_TOKEN: ${{ secrets.NX_CLOUD_ACCESS_TOKEN }}
  NODE_VERSION: '20'
  REGISTRY: ghcr.io
  IMAGE_PREFIX: ${{ github.repository }}

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'

      - name: Restore Nx cache
        uses: actions/cache@v4
        with:
          path: .nx/cache
          key: nx-main-${{ hashFiles('package-lock.json') }}-${{ github.sha }}
          restore-keys: |
            nx-main-${{ hashFiles('package-lock.json') }}-

      - name: Install dependencies
        run: npm ci

      - name: Build all
        run: npx nx run-many --target=build --all

      - name: Test all
        run: npx nx run-many --target=test --all --coverage

      - name: Upload coverage
        uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}

  docker-build:
    needs: build-and-test
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - bff-workstation
          - bff-admin
          - marketdata-ingest
          - rfq-service
          - execution-service
    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login to Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}/${{ matrix.service }}
          tags: |
            type=sha
            type=ref,event=branch
            type=raw,value=latest

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: .
          file: services/${{ matrix.service }}/Dockerfile
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### Proto Validation Workflow (`.github/workflows/proto-validate.yml`)
```yaml
name: Proto Validation

on:
  pull_request:
    paths:
      - 'proto/**'
  push:
    branches: [main]
    paths:
      - 'proto/**'

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Buf
        uses: bufbuild/buf-setup-action@v1
        with:
          version: '1.28.1'

      - name: Buf lint
        run: buf lint proto/

      - name: Buf format check
        run: buf format proto/ --diff --exit-code

      - name: Buf breaking (against main)
        if: github.event_name == 'pull_request'
        run: |
          buf breaking proto/ \
            --against 'https://github.com/${{ github.repository }}.git#branch=main,subdir=proto'

  generate:
    needs: validate
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Buf
        uses: bufbuild/buf-setup-action@v1
        with:
          version: '1.28.1'

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Generate code
        run: |
          cd proto
          buf generate

      - name: Verify generated code compiles
        run: npx tsc --noEmit -p libs/proto-gen/tsconfig.json
```

### Dockerfile Template (`services/template/Dockerfile`)
```dockerfile
# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./
COPY nx.json tsconfig*.json ./

# Install dependencies
RUN npm ci

# Copy source
COPY libs/ ./libs/
COPY services/SERVICE_NAME/ ./services/SERVICE_NAME/

# Build the service
RUN npx nx build SERVICE_NAME --prod

# Production stage
FROM node:20-alpine AS runner

WORKDIR /app

# Add non-root user
RUN addgroup -g 1001 -S orion && \
    adduser -S orion -u 1001 -G orion

# Copy built artifacts
COPY --from=builder /app/dist/services/SERVICE_NAME ./
COPY --from=builder /app/node_modules ./node_modules

# Set ownership
RUN chown -R orion:orion /app

USER orion

EXPOSE 3000

CMD ["node", "main.js"]
```

## Implementation Steps

1. **Create Workflow Directory**
   - Create `.github/workflows/`
   - Create `.github/actions/` for reusable actions

2. **Create PR Workflow**
   - Implement affected-based builds
   - Configure caching
   - Set up test reporting

3. **Create Main Workflow**
   - Full builds for main branch
   - Coverage reporting
   - Artifact generation

4. **Create Proto Workflow**
   - Buf linting
   - Breaking change detection
   - Code generation verification

5. **Create Docker Workflow**
   - Multi-stage builds
   - Container registry push
   - Caching for faster builds

6. **Configure Branch Protection**
   - Require CI status checks
   - Require PR reviews

7. **Document CI Process**
   - Document workflow triggers
   - Document required secrets

## Definition of Done

- [ ] All acceptance criteria met
- [ ] PR workflow runs successfully
- [ ] Main workflow builds and pushes images
- [ ] Proto validation catches breaking changes
- [ ] Branch protection rules configured
- [ ] Documentation complete
- [ ] Secrets configured in repository

## Dependencies

- US-01-01: Initialize Monorepo
- US-01-06: Protobuf Definitions (for proto validation)

## Required Secrets

| Secret | Purpose |
|--------|---------|
| `GITHUB_TOKEN` | Automatic, for registry push |
| `NX_CLOUD_ACCESS_TOKEN` | Optional, for remote caching |
| `CODECOV_TOKEN` | Optional, for coverage reports |

## Notes

- Start with GitHub Container Registry (ghcr.io)
- Can migrate to ECR for production later
- Consider Nx Cloud for remote caching
- Branch protection should be enabled after workflows are verified
