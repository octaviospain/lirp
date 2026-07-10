# Security Policy

LIRP is a JVM persistence library distributed on Maven Central. It runs **inside the
consuming application's process** — it is not a network service and exposes no listening
endpoint of its own. Its security surface is therefore the data it (de)serializes, the SQL
it generates, and the integrity of the dependency graph it ships. This policy covers how to
report a vulnerability and what protections are already in place.

## Supported Versions

Security fixes are released against the **most recent minor line**. Older lines do not
receive backported patches; upgrade to the latest release to stay covered.

| Version | Supported          |
|---------|--------------------|
| 3.2.x   | :white_check_mark: |
| < 3.2   | :x:                |

Releases follow semantic versioning (`X.Y.Z`, no `v` prefix). See [CHANGELOG.md](CHANGELOG.md)
for migration notes on breaking changes.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately through either channel:

1. **GitHub Security Advisories** (preferred) — open a private report via the repository's
   [**Security → Report a vulnerability**](https://github.com/octaviospain/lirp/security/advisories/new)
   tab. This keeps the disclosure confidential and lets us collaborate on a fix and a CVE if
   warranted.
2. **Email** — <octavio@transgressoft.net> if you cannot use GitHub advisories.

Please include, where possible:

- The affected module(s) (`lirp-api`, `lirp-core`, `lirp-sql`, `lirp-fx`, `lirp-kafka`,
  `lirp-ksp`, `lirp-gradle-plugin`) and version.
- A description of the vulnerability and its impact.
- A minimal reproduction — a failing test, code snippet, or steps.
- Any known mitigations or workarounds.

### What to expect

- **Acknowledgement** within **5 business days**.
- An initial **assessment and severity triage** within **10 business days**.
- Coordinated disclosure: we will agree on a timeline before any public detail is published,
  aiming to ship a fix in a patch release before disclosure. Credit is given to reporters who
  wish to be named.

This is an open-source project maintained on a best-effort basis; timelines are targets, not
contractual guarantees.

## Security Considerations When Using LIRP

LIRP inherits the trust boundaries of the application embedding it. Keep the following in mind:

- **SQL persistence** — `lirp-sql` builds queries through JetBrains Exposed, which uses
  parameterized statements. The type-safe query DSL does not interpolate user input into SQL
  strings. If you extend it with raw SQL, parameterize your own inputs.
- **JSON persistence** — `LirpEntitySerializer` (built on Kotlin Serialization) reads and
  writes JSON. Only deserialize files from trusted locations. As with any persistence layer,
  a JSON store is a confidentiality and integrity boundary you own — protect it with
  filesystem permissions.
- **No encryption at rest** — LIRP does not encrypt database contents or JSON files. Use
  database-level encryption, an encrypted filesystem, or column-level encryption in your
  domain model if you persist sensitive data.
- **Connection credentials** — database URLs, usernames, and passwords are supplied by the
  consumer (e.g. via HikariCP configuration). Never hard-code them; source them from your
  application's secret management.
- **Kafka integration** — `lirp-kafka` publishes mutation events through a transactional
  outbox. Secure the broker (TLS, SASL/ACLs) at the Kafka client configuration you pass in;
  LIRP does not weaken or override those settings.
- **Optimistic persistence** — repositories are in-memory-first with a debounced write
  pipeline. A crash between an enqueued mutation and its flush can lose uncommitted data.
  This is a durability trade-off documented in the README, not an access-control mechanism.

## Supply-Chain Security

The build and release pipeline is hardened against dependency- and CI-level tampering. Full
detail lives in [CONTRIBUTING.md → Supply Chain Security](CONTRIBUTING.md#supply-chain-security);
in summary:

- **Dependency verification** — `gradle/verification-metadata.xml` pins SHA-256 checksums for
  every resolved artifact (direct, transitive, and Gradle plugins). A checksum mismatch fails
  the build.
- **PR dependency gate** — `actions/dependency-review-action` fails pull requests that
  introduce dependencies with HIGH+ severity CVEs.
- **Continuous scanning** — a weekly `osv-scanner` run scans the runtime dependency graph and
  uploads SARIF results to the GitHub Security tab; the runtime gate is blocking, with
  build/plugin-classpath findings surfaced separately as advisory-only.
- **CycloneDX SBOM** — each release publishes an aggregated CycloneDX 1.5 SBOM
  (`bom.json`) as a release artifact for downstream scanners (Trivy, Grype, etc.).
- **Pinned CI actions** — all GitHub Actions `uses:` references are pinned to commit SHAs to
  prevent mutable-tag hijacking.
- **Automated updates** — Renovate keeps dependencies, actions, and the Gradle wrapper current,
  with CVE-flagged updates bypassing the normal schedule.

## Scope

In scope: vulnerabilities in LIRP's own source across its published modules, and in the
build/release tooling that could compromise shipped artifacts.

Out of scope: vulnerabilities in third-party dependencies with no LIRP-specific exploit path
(report those upstream; we track them via the scanning above), issues requiring a
pre-compromised host or malicious build environment, and misconfigurations in consuming
applications (e.g. exposing an unencrypted database to an untrusted network).
