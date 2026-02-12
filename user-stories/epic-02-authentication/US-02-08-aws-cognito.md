# User Story: US-02-08 - AWS Cognito Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-08 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | AWS Cognito Configuration for Production |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** platform operator  
**I want** AWS Cognito configured as the production OIDC provider  
**So that** we have enterprise-grade authentication

## Acceptance Criteria

- [ ] Cognito User Pool created via Terraform
- [ ] App clients configured for each environment
- [ ] Custom domain for auth endpoints
- [ ] JWT claims match expected structure
- [ ] MFA can be enabled per-tenant
- [ ] BFF switches IdP based on environment

## Technical Details

### Terraform Configuration
```hcl
resource "aws_cognito_user_pool" "orion" {
  name = "orion-${var.environment}"

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  schema {
    name                = "tenant_id"
    attribute_data_type = "String"
    mutable             = false
  }

  schema {
    name                = "roles"
    attribute_data_type = "String"
    mutable             = true
  }
}

resource "aws_cognito_user_pool_client" "bff" {
  name         = "orion-bff"
  user_pool_id = aws_cognito_user_pool.orion.id

  generate_secret                      = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  callback_urls                        = var.callback_urls
  logout_urls                          = var.logout_urls
  supported_identity_providers         = ["COGNITO"]

  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }

  access_token_validity  = 1
  id_token_validity      = 1
  refresh_token_validity = 30
}

resource "aws_cognito_user_pool_domain" "orion" {
  domain          = "auth-${var.environment}-orion"
  certificate_arn = var.acm_certificate_arn
  user_pool_id    = aws_cognito_user_pool.orion.id
}
```

### Environment-Aware IdP Config
```typescript
const oidcConfig = {
  development: {
    issuer: 'http://localhost:8080/realms/orion',
    clientId: 'orion-bff',
    clientSecret: process.env.KEYCLOAK_CLIENT_SECRET,
  },
  staging: {
    issuer: `https://cognito-idp.${process.env.AWS_REGION}.amazonaws.com/${process.env.USER_POOL_ID}`,
    clientId: process.env.COGNITO_CLIENT_ID,
    clientSecret: process.env.COGNITO_CLIENT_SECRET,
  },
  production: {
    issuer: `https://cognito-idp.${process.env.AWS_REGION}.amazonaws.com/${process.env.USER_POOL_ID}`,
    clientId: process.env.COGNITO_CLIENT_ID,
    clientSecret: process.env.COGNITO_CLIENT_SECRET,
  },
}[process.env.NODE_ENV || 'development'];
```

## Definition of Done

- [ ] Terraform modules validated
- [ ] JWT claims structure matches Keycloak
- [ ] BFF works with both IdPs
- [ ] Documentation updated
- [ ] Runbook for user management created
