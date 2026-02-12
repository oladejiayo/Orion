# User Story: US-01-08 - Setup Code Quality Tools and Standards

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-08 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Code Quality Tools and Standards |
| **Priority** | P1 - High |
| **Story Points** | 3 |
| **Type** | Technical Foundation |

## User Story

**As a** development team member  
**I want** consistent code quality tools (linting, formatting, commit hooks)  
**So that** the codebase maintains high quality and consistent style across all contributors

## Acceptance Criteria

### AC1: ESLint Configuration
- [ ] ESLint configured with TypeScript support
- [ ] Nx ESLint plugin integrated
- [ ] Custom rules for Orion conventions
- [ ] Separate configs for services, libs, and frontend

### AC2: Prettier Configuration
- [ ] Prettier configured for consistent formatting
- [ ] Integration with ESLint (no conflicts)
- [ ] Format on save enabled in VS Code settings
- [ ] `npm run format` formats all code

### AC3: Git Hooks (Husky)
- [ ] Pre-commit hook runs lint-staged
- [ ] Lint-staged runs ESLint and Prettier on staged files
- [ ] Commit message validation (conventional commits)
- [ ] Pre-push hook runs affected tests

### AC4: Conventional Commits
- [ ] Commitlint configured
- [ ] Commit message format enforced
- [ ] Types: feat, fix, docs, style, refactor, test, chore

### AC5: VS Code Settings
- [ ] Workspace settings for ESLint
- [ ] Workspace settings for Prettier
- [ ] Recommended extensions list
- [ ] Debug configurations

### AC6: EditorConfig
- [ ] `.editorconfig` for cross-IDE consistency
- [ ] Indent style and size defined
- [ ] Line ending normalization

## Technical Details

### Files to Create

#### `.eslintrc.json`
```json
{
  "root": true,
  "ignorePatterns": ["**/*"],
  "plugins": ["@nx"],
  "overrides": [
    {
      "files": ["*.ts", "*.tsx", "*.js", "*.jsx"],
      "rules": {
        "@nx/enforce-module-boundaries": [
          "error",
          {
            "enforceBuildableLibDependency": true,
            "allow": [],
            "depConstraints": [
              {
                "sourceTag": "scope:shared",
                "onlyDependOnLibsWithTags": ["scope:shared"]
              },
              {
                "sourceTag": "scope:service",
                "onlyDependOnLibsWithTags": ["scope:shared", "scope:service"]
              },
              {
                "sourceTag": "scope:web",
                "onlyDependOnLibsWithTags": ["scope:shared", "scope:web"]
              }
            ]
          }
        ]
      }
    },
    {
      "files": ["*.ts", "*.tsx"],
      "extends": ["plugin:@nx/typescript"],
      "rules": {
        "@typescript-eslint/no-unused-vars": ["error", { "argsIgnorePattern": "^_" }],
        "@typescript-eslint/explicit-function-return-type": "off",
        "@typescript-eslint/no-explicit-any": "warn"
      }
    }
  ]
}
```

#### `.prettierrc`
```json
{
  "singleQuote": true,
  "trailingComma": "es5",
  "printWidth": 100,
  "tabWidth": 2,
  "semi": true,
  "bracketSpacing": true,
  "arrowParens": "always",
  "endOfLine": "lf"
}
```

#### `.husky/pre-commit`
```bash
#!/bin/sh
. "$(dirname "$0")/_/husky.sh"

npx lint-staged
```

#### `.husky/commit-msg`
```bash
#!/bin/sh
. "$(dirname "$0")/_/husky.sh"

npx --no-install commitlint --edit "$1"
```

#### `commitlint.config.js`
```javascript
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      ['feat', 'fix', 'docs', 'style', 'refactor', 'perf', 'test', 'chore', 'revert', 'ci']
    ],
    'scope-enum': [
      1,
      'always',
      ['core', 'bff', 'rfq', 'marketdata', 'execution', 'posttrade', 'admin', 'ui', 'infra', 'docs']
    ],
    'subject-case': [2, 'always', 'lower-case']
  }
};
```

#### `.lintstagedrc.json`
```json
{
  "*.{ts,tsx,js,jsx}": ["eslint --fix", "prettier --write"],
  "*.{json,md,yml,yaml}": ["prettier --write"],
  "*.proto": ["buf format -w"]
}
```

#### `.vscode/settings.json`
```json
{
  "editor.defaultFormatter": "esbenp.prettier-vscode",
  "editor.formatOnSave": true,
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": "explicit"
  },
  "eslint.validate": ["typescript", "javascript"],
  "typescript.preferences.importModuleSpecifier": "relative"
}
```

#### `.vscode/extensions.json`
```json
{
  "recommendations": [
    "esbenp.prettier-vscode",
    "dbaeumer.vscode-eslint",
    "nrwl.angular-console",
    "zixuanchen.vitest-explorer",
    "bufbuild.vscode-buf"
  ]
}
```

#### `.editorconfig`
```ini
root = true

[*]
indent_style = space
indent_size = 2
end_of_line = lf
charset = utf-8
trim_trailing_whitespace = true
insert_final_newline = true

[*.md]
trim_trailing_whitespace = false
```

## Implementation Steps

1. Install dependencies:
   ```bash
   npm install -D eslint prettier husky lint-staged @commitlint/cli @commitlint/config-conventional
   ```

2. Initialize Husky:
   ```bash
   npx husky install
   npm pkg set scripts.prepare="husky install"
   ```

3. Create configuration files
4. Test hooks work correctly
5. Document conventions

## Definition of Done

- [ ] All acceptance criteria met
- [ ] `npm run lint` works
- [ ] `npm run format` works
- [ ] Pre-commit hook catches issues
- [ ] Commit message validation works
- [ ] Documentation complete

## Dependencies

- US-01-01: Initialize Monorepo
