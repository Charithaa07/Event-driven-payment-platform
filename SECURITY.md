# Security Policy

## Supported code

This portfolio project is maintained on the `main` branch. Security fixes are applied to the latest code rather than backported to historical phase branches.

## Reporting a vulnerability

Please do not publish exploit details, credentials, tokens, or proof-of-concept payloads in a public issue.

If GitHub private vulnerability reporting is available for this repository, use the repository **Security** tab to submit the report privately. Otherwise, open a public issue containing only a short request for a private security contact and omit technical exploit details until a private channel is established.

A useful report includes:

- affected service or infrastructure component;
- the vulnerable endpoint, dependency, image, or configuration;
- reproduction conditions without real credentials or customer data;
- expected security boundary and observed behavior;
- likely impact;
- a suggested mitigation when known.

## Security scope

The repository uses automated static analysis, dependency review, secret scanning, infrastructure misconfiguration scanning, container vulnerability scanning, SBOM generation, OAuth2/JWT authorization, non-root containers, AWS short-lived identity, and workload-specific Kafka permissions.

These controls reduce risk but are not a claim that the software is vulnerability-free. The AWS configuration is a validated reference architecture until it is applied and tested in a real AWS account.
