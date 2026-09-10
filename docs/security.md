# Security and software-supply-chain controls

Phase 16 adds executable security checks around the existing application, infrastructure, and deployment layers. The goal is to make security evidence reproducible while keeping correctness tests and vulnerability intelligence separate.

## Security boundaries already enforced by the platform

- Payment APIs validate OAuth2/OIDC JWTs and enforce customer ownership from the token subject.
- Operational, audit, and notification query endpoints use explicit scopes.
- Payment request idempotency is scoped by authenticated customer identity.
- Containers run as a dedicated non-root UID/GID.
- AWS deployment uses GitHub OIDC rather than long-lived AWS access keys.
- EKS workloads use Pod Identity rather than static AWS credentials.
- RDS credentials are managed by AWS Secrets Manager.
- MSK uses IAM/SASL and workload-specific topic/consumer-group permissions.
- Transaction, audit, and notification consumers preserve durable idempotency boundaries under at-least-once Kafka delivery.

## Automated security workflow

`.github/workflows/security.yml` runs on pull requests, pushes to `main`, a weekly schedule, and manual dispatch.

### CodeQL

Java source is analyzed with CodeQL's `security-extended` query suite. CodeQL is intentionally separate from the normal Maven correctness job so a security-analysis failure is visible as its own control.

### Trivy repository scan

Trivy 0.74.0 scans three different risk classes:

1. committed secrets are a hard failure;
2. HIGH/CRITICAL Terraform and Kubernetes misconfigurations are a hard failure;
3. fixed CRITICAL dependency vulnerabilities are a hard failure.

A broader HIGH/CRITICAL SARIF report is also generated and uploaded to GitHub code scanning. `--ignore-unfixed` is used for vulnerability gates so the build does not fail on a package for which no upstream fix exists; those findings remain visible in the report and should still be assessed.

GitHub's Dependency Review API is not used as a required gate because it depends on the repository-level Dependency Graph feature being enabled. The Maven dependency set is still scanned directly by Trivy, and Dependabot is configured for proactive updates.

### Container image scan

The workflow packages all four Spring Boot services, builds the same runtime images used by the deployment pipeline, and scans each image for fixed CRITICAL vulnerabilities. This catches vulnerabilities introduced by both Java dependencies and the operating-system/runtime layer.

The image scan does not replace runtime hardening, admission control, or registry-side continuous scanning in a real production account.

## SBOM evidence

The workflow generates:

- one aggregate CycloneDX SBOM for the Java reactor using `cyclonedx-maven-plugin` 2.9.3;
- one CycloneDX image SBOM for each of Payment, Transaction, Audit, and Notification services.

Security evidence is retained as a GitHub Actions artifact for 14 days. SBOM generation records what was built; it is not by itself a vulnerability verdict.

## Dependency maintenance

`.github/dependabot.yml` performs weekly checks for:

- Maven dependencies;
- GitHub Actions;
- the runtime Docker base image;
- Terraform provider dependencies.

Minor and patch Maven updates are grouped to reduce update noise. Major updates remain visible as independent compatibility decisions.

## Local reproduction

Package the services first:

```bash
mvn --batch-mode -DskipTests package
```

Run the same Trivy version used in CI:

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace aquasec/trivy:0.74.0 \
  fs --scanners secret --exit-code 1 --no-progress .

docker run --rm -v "$PWD:/workspace" -w /workspace aquasec/trivy:0.74.0 \
  fs --scanners misconfig --severity HIGH,CRITICAL --exit-code 1 --no-progress deploy

docker run --rm -v "$PWD:/workspace" -w /workspace aquasec/trivy:0.74.0 \
  fs --scanners vuln --severity CRITICAL --ignore-unfixed --exit-code 1 --no-progress .
```

Generate the aggregate Java SBOM:

```bash
mvn --batch-mode org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
  -DoutputFormat=json -DoutputName=platform-sbom
```

## Triage rules

A failed security gate should not be bypassed simply to restore a green badge. For each finding:

1. confirm that the vulnerable component or configuration is reachable in this architecture;
2. upgrade or remove the affected dependency/configuration when a compatible fix exists;
3. add a narrowly scoped exception only when the finding is demonstrably not applicable;
4. document the reason, affected version, compensating control, owner, and expiration/review date for any exception.

No blanket Trivy ignore file is committed in Phase 16. If an exception becomes necessary later, it should identify an exact finding rather than suppress an entire scanner or severity class.

## What the security workflow does not prove

A green run does not prove that the platform is secure. It means the checked source, dependencies, infrastructure configuration, and built images passed the defined controls against the vulnerability databases available at scan time. Penetration testing, cloud-runtime verification, IAM access analysis, network-policy enforcement, WAF/rate limiting, runtime detection, backup/restore exercises, and incident response remain separate concerns.
