# Security Policy

## Supported version

This portfolio project supports the current `main` branch.

## Reporting a vulnerability

Please do not open a public issue for a suspected exploitable vulnerability. Use GitHub's private vulnerability reporting flow when available, or contact the repository owner privately.

Include:

- affected component and commit SHA
- reproduction steps
- expected impact
- proof-of-concept details when safe to share
- any known mitigations

## Security controls in this repository

The project uses layered controls rather than treating a passing unit test as a security signal:

- OAuth2/JWT resource-server authorization and scoped APIs
- customer-scoped idempotency and resource ownership checks
- non-root runtime containers
- Kubernetes security context and namespace-scoped deployment access
- AWS OIDC federation and EKS Pod Identity instead of long-lived AWS credentials
- RDS-managed Secrets Manager credentials
- least-privilege per-service MSK IAM permissions
- Trivy source, secret, IaC, and runtime-image scanning in CI
- CycloneDX SBOM generation for all four runtime images
- automated dependency update PRs through Dependabot

## CI vulnerability policy

CI fails on fixable `HIGH` or `CRITICAL` findings in source dependencies, committed secrets, infrastructure configuration, or runtime container images. Unfixed upstream vulnerabilities are reported but are not used as a hard gate so the repository does not become permanently blocked on issues outside the project's control.

Security-tool output is evidence for triage, not proof that the system is vulnerability-free. Findings should be reviewed for exploitability, reachability, and available remediation before release.

## Secrets

Do not commit real credentials, private keys, production JWTs, AWS access keys, database passwords, provider API keys, or Terraform state. Example values must remain non-sensitive placeholders.
