# User Story: US-01-01 - Initialize Monorepo with Build Tool

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-01 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Initialize Monorepo with Build Tool |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** development team member  
**I want** a properly configured monorepo with build tooling  
**So that** I can develop, build, and test multiple services and libraries in a single repository with efficient caching and dependency management

## Description

This story establishes the foundational repository structure using a modern monorepo build tool (Nx recommended). The monorepo will host all Orion services, shared libraries, frontend applications, and infrastructure code. Proper configuration will enable incremental builds, affected-based testing, and consistent developer experience across all projects.

## Acceptance Criteria

### AC1: Repository Root Structure
- [ ] Root `package.json` is created with workspace configuration
- [ ] Monorepo tool (Nx) is installed and configured
- [ ] `nx.json` configuration file exists with sensible defaults
- [ ] `.gitignore` properly excludes build outputs, node_modules, and IDE files
- [ ] Root `tsconfig.base.json` establishes TypeScript baseline configuration

### AC2: Workspace Configuration
- [ ] Workspace is configured to recognize `/services/*` as applications
- [ ] Workspace is configured to recognize `/libs/*` as libraries
- [ ] Workspace is configured to recognize `/web/*` as frontend applications
- [ ] Project naming convention is established and documented
- [ ] Path aliases are configured for library imports (e.g., `@orion/event-model`)

### AC3: Directory Structure
- [ ] All directories from PRD structure are created (even if empty with .gitkeep)
- [ ] Each directory has appropriate README.md placeholder
- [ ] Directory structure matches:
```
/docs
  /prd
  /architecture  
  /runbooks
/services
/libs
/proto
/schemas
/infra
  /terraform
  /docker-compose
/benchmarks
/scripts
/web
```

### AC4: Build Commands
- [ ] `npm install` from root installs all dependencies
- [ ] `nx build <project>` builds a specific project
- [ ] `nx test <project>` runs tests for a specific project
- [ ] `nx affected:build` builds only affected projects
- [ ] `nx affected:test` tests only affected projects
- [ ] `nx graph` generates dependency graph visualization

### AC5: TypeScript Configuration
- [ ] Strict TypeScript mode is enabled
- [ ] Path mappings work across all projects
- [ ] Incremental compilation is enabled
- [ ] Source maps are generated for debugging
- [ ] Declaration files are generated for libraries

## Technical Details

### Technology Stack
- **Node.js:** 20.x LTS
- **Package Manager:** npm 10.x or pnpm 8.x
- **Monorepo Tool:** Nx 18.x
- **TypeScript:** 5.x
- **Build System:** esbuild via Nx plugins

### Configuration Files to Create

#### Root `package.json`
```json
{
  "name": "@orion/source",
  "version": "0.0.0",
  "private": true,
  "workspaces": [
    "services/*",
    "libs/*",
    "web/*"
  ],
  "scripts": {
    "build": "nx build",
    "test": "nx test",
    "lint": "nx lint",
    "format": "nx format:write",
    "affected:build": "nx affected:build",
    "affected:test": "nx affected:test",
    "graph": "nx graph"
  },
  "devDependencies": {
    "@nx/js": "^18.0.0",
    "@nx/node": "^18.0.0",
    "@nx/react": "^18.0.0",
    "nx": "^18.0.0",
    "typescript": "^5.3.0"
  }
}
```

#### `nx.json`
```json
{
  "$schema": "./node_modules/nx/schemas/nx-schema.json",
  "targetDefaults": {
    "build": {
      "cache": true,
      "dependsOn": ["^build"]
    },
    "test": {
      "cache": true
    },
    "lint": {
      "cache": true
    }
  },
  "defaultBase": "main",
  "namedInputs": {
    "default": ["{projectRoot}/**/*", "sharedGlobals"],
    "production": ["default", "!{projectRoot}/**/*.spec.ts"],
    "sharedGlobals": []
  }
}
```

#### `tsconfig.base.json`
```json
{
  "compileOnSave": false,
  "compilerOptions": {
    "rootDir": ".",
    "sourceMap": true,
    "declaration": true,
    "moduleResolution": "node",
    "emitDecoratorMetadata": true,
    "experimentalDecorators": true,
    "importHelpers": true,
    "target": "ES2022",
    "module": "NodeNext",
    "lib": ["ES2022"],
    "skipLibCheck": true,
    "skipDefaultLibCheck": true,
    "strict": true,
    "noImplicitAny": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "forceConsistentCasingInFileNames": true,
    "baseUrl": ".",
    "paths": {
      "@orion/event-model": ["libs/event-model/src/index.ts"],
      "@orion/security": ["libs/security/src/index.ts"],
      "@orion/observability": ["libs/observability/src/index.ts"],
      "@orion/common": ["libs/common/src/index.ts"]
    }
  },
  "exclude": ["node_modules", "dist"]
}
```

### Implementation Steps

1. **Initialize Repository**
   ```bash
   mkdir orion && cd orion
   git init
   npx create-nx-workspace@latest . --preset=ts
   ```

2. **Create Directory Structure**
   - Create all directories per PRD specification
   - Add `.gitkeep` files to empty directories
   - Add placeholder README.md files

3. **Configure TypeScript**
   - Create `tsconfig.base.json` with strict settings
   - Configure path aliases for all libraries

4. **Configure Nx**
   - Set up project detection patterns
   - Configure caching settings
   - Set up task pipelines

5. **Verify Setup**
   - Run `nx report` to verify configuration
   - Run `nx graph` to see empty dependency graph
   - Ensure all commands work without errors

### File Structure After Completion
```
/
├── .gitignore
├── .gitkeep files in empty dirs
├── nx.json
├── package.json
├── package-lock.json
├── tsconfig.base.json
├── docs/
│   ├── prd/
│   │   └── README.md
│   ├── architecture/
│   │   └── README.md
│   └── runbooks/
│       └── README.md
├── services/
│   └── README.md
├── libs/
│   └── README.md
├── proto/
│   └── v1/
│       └── README.md
├── schemas/
│   └── v1/
│       └── README.md
├── infra/
│   ├── terraform/
│   │   └── README.md
│   └── docker-compose/
│       └── README.md
├── benchmarks/
│   └── README.md
├── scripts/
│   └── README.md
└── web/
    └── README.md
```

## Definition of Done

- [ ] All acceptance criteria are met
- [ ] Code has been reviewed and approved
- [ ] `npm install` completes without errors
- [ ] All Nx commands work correctly
- [ ] TypeScript compilation succeeds
- [ ] Directory structure matches specification
- [ ] README files explain purpose of each directory
- [ ] Branch merged to main

## Dependencies

- None (first story in the epic)

## Downstream Dependencies

All subsequent stories depend on this one.

## Testing Requirements

### Manual Testing
1. Clone repository fresh
2. Run `npm install` - should complete without errors
3. Run `nx graph` - should display empty graph
4. Run `nx report` - should show Nx configuration
5. Verify TypeScript path aliases resolve correctly

## Notes

- Consider using pnpm for faster installs if team agrees
- Nx cloud can be configured later for remote caching
- Keep configuration minimal initially; add plugins as needed

## Related Documentation

- [Nx Documentation](https://nx.dev/getting-started/intro)
- [TypeScript Documentation](https://www.typescriptlang.org/docs/)
- [Monorepo Best Practices](https://nx.dev/more-concepts/why-monorepos)
