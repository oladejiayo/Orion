# US-15-05: CI/CD Pipeline Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-15-05 |
| **Epic** | Epic 15: AWS Infrastructure |
| **Title** | CI/CD Pipeline Configuration |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** developer  
**I want** automated CI/CD pipelines  
**So that** code changes are tested, secured, and deployed consistently

## Acceptance Criteria

### AC1: Build Pipeline
- **Given** code pushed to repository
- **When** pipeline triggers
- **Then**:
  - Code compiled
  - Unit tests run
  - Code quality checks
  - Docker images built

### AC2: Security Scanning
- **Given** build artifacts
- **When** security stage runs
- **Then**:
  - SAST analysis
  - Dependency scanning
  - Container image scanning
  - Secrets detection

### AC3: Deployment Pipeline
- **Given** passing builds
- **When** deployment triggers
- **Then**:
  - Images pushed to ECR
  - Kubernetes manifests updated
  - Blue/green deployment
  - Automatic rollback

### AC4: Environment Promotion
- **Given** successful staging deployment
- **When** promotion approved
- **Then**:
  - Same image to UAT/prod
  - Manual approval gates
  - Audit trail maintained

### AC5: ArgoCD GitOps
- **Given** manifest changes
- **When** merged to main
- **Then**:
  - ArgoCD syncs automatically
  - Drift detection enabled
  - Rollback capability

## Technical Specification

### GitHub Actions - Build Pipeline

```yaml
# .github/workflows/build.yml
name: Build and Test

on:
  push:
    branches: [main, develop, 'feature/**', 'release/**']
  pull_request:
    branches: [main, develop]

env:
  AWS_REGION: us-east-1
  ECR_REGISTRY: ${{ secrets.AWS_ACCOUNT_ID }}.dkr.ecr.us-east-1.amazonaws.com

jobs:
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.filter.outputs.changes }}
    steps:
      - uses: actions/checkout@v4
      
      - uses: dorny/paths-filter@v2
        id: filter
        with:
          filters: |
            order-service:
              - 'services/order-service/**'
            position-service:
              - 'services/position-service/**'
            market-data-service:
              - 'services/market-data-service/**'
            gateway:
              - 'services/gateway/**'
            frontend:
              - 'frontend/**'

  build-java-services:
    needs: detect-changes
    if: contains(needs.detect-changes.outputs.services, 'order-service') || contains(needs.detect-changes.outputs.services, 'position-service')
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [order-service, position-service, market-data-service]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Build with Maven
        run: |
          cd services/${{ matrix.service }}
          mvn clean verify -B -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
      
      - name: Run Unit Tests
        run: |
          cd services/${{ matrix.service }}
          mvn test -B
      
      - name: Upload Test Results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-results-${{ matrix.service }}
          path: services/${{ matrix.service }}/target/surefire-reports
      
      - name: Upload Coverage Report
        uses: actions/upload-artifact@v4
        with:
          name: coverage-${{ matrix.service }}
          path: services/${{ matrix.service }}/target/site/jacoco

  build-frontend:
    needs: detect-changes
    if: contains(needs.detect-changes.outputs.services, 'frontend')
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json
      
      - name: Install Dependencies
        run: |
          cd frontend
          npm ci
      
      - name: Type Check
        run: |
          cd frontend
          npm run typecheck
      
      - name: Lint
        run: |
          cd frontend
          npm run lint
      
      - name: Run Tests
        run: |
          cd frontend
          npm run test:coverage
      
      - name: Build
        run: |
          cd frontend
          npm run build
      
      - name: Upload Build Artifacts
        uses: actions/upload-artifact@v4
        with:
          name: frontend-build
          path: frontend/dist

  code-quality:
    runs-on: ubuntu-latest
    needs: [build-java-services, build-frontend]
    if: always()
    
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      
      - name: SonarCloud Scan
        uses: SonarSource/sonarcloud-github-action@master
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        with:
          args: >
            -Dsonar.organization=${{ vars.SONAR_ORG }}
            -Dsonar.projectKey=${{ vars.SONAR_PROJECT_KEY }}
```

### GitHub Actions - Security Pipeline

```yaml
# .github/workflows/security.yml
name: Security Scanning

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]
  schedule:
    - cron: '0 6 * * 1'  # Weekly on Monday

jobs:
  dependency-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Run Trivy vulnerability scanner (repo)
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'
          format: 'sarif'
          output: 'trivy-results.sarif'
          severity: 'CRITICAL,HIGH'
      
      - name: Upload Trivy scan results
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: 'trivy-results.sarif'

  sast-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: java, javascript
      
      - name: Autobuild
        uses: github/codeql-action/autobuild@v3
      
      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v3

  secrets-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      
      - name: GitLeaks Scan
        uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  container-scan:
    runs-on: ubuntu-latest
    needs: [dependency-scan]
    strategy:
      matrix:
        service: [order-service, position-service, gateway, frontend]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Build Docker Image
        run: |
          docker build -t ${{ matrix.service }}:scan -f services/${{ matrix.service }}/Dockerfile services/${{ matrix.service }}
      
      - name: Run Trivy container scanner
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: '${{ matrix.service }}:scan'
          format: 'sarif'
          output: 'trivy-container-${{ matrix.service }}.sarif'
          severity: 'CRITICAL,HIGH'
          ignore-unfixed: true
      
      - name: Upload container scan results
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: 'trivy-container-${{ matrix.service }}.sarif'
          category: 'container-${{ matrix.service }}'
```

### GitHub Actions - Deploy Pipeline

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  workflow_dispatch:
    inputs:
      environment:
        description: 'Environment to deploy to'
        required: true
        type: choice
        options:
          - dev
          - staging
          - uat
          - prod
      service:
        description: 'Service to deploy (or all)'
        required: true
        type: choice
        options:
          - all
          - order-service
          - position-service
          - market-data-service
          - gateway
          - frontend
  push:
    branches:
      - main
    paths:
      - 'services/**'
      - 'frontend/**'

env:
  AWS_REGION: us-east-1

jobs:
  determine-environment:
    runs-on: ubuntu-latest
    outputs:
      environment: ${{ steps.set-env.outputs.environment }}
    steps:
      - id: set-env
        run: |
          if [ "${{ github.event_name }}" == "workflow_dispatch" ]; then
            echo "environment=${{ inputs.environment }}" >> $GITHUB_OUTPUT
          elif [ "${{ github.ref }}" == "refs/heads/main" ]; then
            echo "environment=staging" >> $GITHUB_OUTPUT
          else
            echo "environment=dev" >> $GITHUB_OUTPUT
          fi

  build-and-push:
    needs: determine-environment
    runs-on: ubuntu-latest
    environment: ${{ needs.determine-environment.outputs.environment }}
    
    strategy:
      matrix:
        service: [order-service, position-service, market-data-service, gateway, frontend]
    
    outputs:
      image-tag: ${{ steps.meta.outputs.version }}
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}
      
      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2
      
      - name: Docker meta
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ steps.login-ecr.outputs.registry }}/orion-${{ matrix.service }}
          tags: |
            type=sha,prefix=
            type=ref,event=branch
            type=semver,pattern={{version}}
      
      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: services/${{ matrix.service }}
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  update-manifests:
    needs: [determine-environment, build-and-push]
    runs-on: ubuntu-latest
    environment: ${{ needs.determine-environment.outputs.environment }}
    
    steps:
      - name: Checkout GitOps repo
        uses: actions/checkout@v4
        with:
          repository: ${{ github.repository_owner }}/orion-gitops
          token: ${{ secrets.GITOPS_PAT }}
          path: gitops
      
      - name: Update image tags
        run: |
          cd gitops/environments/${{ needs.determine-environment.outputs.environment }}
          
          # Update kustomization.yaml with new image tags
          for service in order-service position-service gateway frontend; do
            yq e -i ".images[] |= select(.name == \"*/$service\").newTag = \"${{ needs.build-and-push.outputs.image-tag }}\"" kustomization.yaml
          done
      
      - name: Commit and push
        run: |
          cd gitops
          git config user.name "GitHub Actions"
          git config user.email "actions@github.com"
          git add .
          git commit -m "Deploy ${{ needs.build-and-push.outputs.image-tag }} to ${{ needs.determine-environment.outputs.environment }}"
          git push

  deploy-to-staging:
    needs: [update-manifests, determine-environment]
    if: needs.determine-environment.outputs.environment == 'staging'
    runs-on: ubuntu-latest
    environment:
      name: staging
      url: https://staging.orion.example.com
    
    steps:
      - name: Wait for ArgoCD sync
        run: |
          # ArgoCD will automatically sync from GitOps repo
          echo "Deployment triggered - ArgoCD will sync automatically"
      
      - name: Run smoke tests
        run: |
          # Run basic health checks
          curl -f https://staging-api.orion.example.com/health || exit 1
      
      - name: Notify deployment
        uses: slackapi/slack-github-action@v1.25.0
        with:
          payload: |
            {
              "text": "Deployed to staging: ${{ needs.build-and-push.outputs.image-tag }}"
            }
        env:
          SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK }}

  deploy-to-production:
    needs: [determine-environment, build-and-push]
    if: needs.determine-environment.outputs.environment == 'prod'
    runs-on: ubuntu-latest
    environment:
      name: production
      url: https://orion.example.com
    
    steps:
      - name: Checkout GitOps repo
        uses: actions/checkout@v4
        with:
          repository: ${{ github.repository_owner }}/orion-gitops
          token: ${{ secrets.GITOPS_PAT }}
      
      - name: Blue/Green Deployment
        run: |
          echo "Initiating blue/green deployment"
          # ArgoCD handles blue/green via Argo Rollouts
      
      - name: Production smoke tests
        run: |
          # Production verification
          for i in {1..5}; do
            curl -f https://api.orion.example.com/health && break || sleep 30
          done
```

### ArgoCD Application Manifests

```yaml
# gitops/base/argocd/application.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: orion-platform
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  
  source:
    repoURL: https://github.com/org/orion-gitops.git
    targetRevision: HEAD
    path: environments/{{.Values.environment}}
  
  destination:
    server: https://kubernetes.default.svc
    namespace: orion
  
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
      allowEmpty: false
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
      - PruneLast=true
    retry:
      limit: 5
      backoff:
        duration: 5s
        factor: 2
        maxDuration: 3m
  
  revisionHistoryLimit: 10
```

### Argo Rollouts for Blue/Green

```yaml
# gitops/base/rollouts/order-service-rollout.yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: order-service
  namespace: orion
spec:
  replicas: 3
  revisionHistoryLimit: 3
  selector:
    matchLabels:
      app: order-service
  
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: ECR_REGISTRY/orion-order-service:latest
          ports:
            - containerPort: 8080
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
  
  strategy:
    blueGreen:
      activeService: order-service-active
      previewService: order-service-preview
      autoPromotionEnabled: false
      scaleDownDelaySeconds: 30
      previewReplicaCount: 1
      
      # Analysis before promotion
      prePromotionAnalysis:
        templates:
          - templateName: success-rate
        args:
          - name: service-name
            value: order-service-preview
      
      # Anti-affinity for blue/green pods
      antiAffinity:
        preferredDuringSchedulingIgnoredDuringExecution:
          weight: 100
```

### Analysis Template

```yaml
# gitops/base/rollouts/analysis-template.yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: success-rate
  namespace: orion
spec:
  args:
    - name: service-name
  metrics:
    - name: success-rate
      interval: 60s
      count: 5
      successCondition: result[0] >= 0.95
      failureLimit: 3
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_server_requests_seconds_count{
              service="{{args.service-name}}",
              status=~"2.."
            }[5m])) /
            sum(rate(http_server_requests_seconds_count{
              service="{{args.service-name}}"
            }[5m]))
    
    - name: error-rate
      interval: 60s
      count: 5
      successCondition: result[0] < 0.01
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_server_requests_seconds_count{
              service="{{args.service-name}}",
              status=~"5.."
            }[5m])) /
            sum(rate(http_server_requests_seconds_count{
              service="{{args.service-name}}"
            }[5m]))
```

## Definition of Done

- [ ] Build pipeline with multi-service support
- [ ] Unit test execution with coverage
- [ ] SAST/DAST security scanning
- [ ] Container image scanning
- [ ] ECR image push
- [ ] GitOps manifest updates
- [ ] ArgoCD sync configuration
- [ ] Blue/green deployment strategy
- [ ] Analysis templates for promotion
- [ ] Slack/Teams notifications
- [ ] Documentation complete

## Test Cases

```yaml
# Test pipeline execution
test-cases:
  - name: "Build triggers on PR"
    given: "PR created to main"
    when: "GitHub Actions triggers"
    then: "All jobs run successfully"
  
  - name: "Security scan blocks on critical"
    given: "Critical vulnerability found"
    when: "Security scan completes"
    then: "Pipeline fails and PR blocked"
  
  - name: "Blue/green promotion"
    given: "Preview deployment passes analysis"
    when: "Promotion approved"
    then: "Traffic shifts to new version"
```
