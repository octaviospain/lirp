# Contributing to lirp

Thank you for your interest in contributing to lirp! This document provides guidelines and instructions for contributing to this project.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Enhancements](#suggesting-enhancements)
- [Pull Requests](#pull-requests)
- [Running Tests](#running-tests)
- [Style Guidelines](#style-guidelines)
- [Public API Compatibility (ABI)](#public-api-compatibility-abi)
- [Supply Chain Security](#supply-chain-security)

## Code of Conduct

This project adheres to a Code of Conduct that sets expectations for participation. By participating, you are expected to uphold this code. Please report unacceptable behavior to [your-email@example.com].

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report, please check the existing issues to avoid duplicates. When you create a bug report, include as many details as possible:

- **Use a clear and descriptive title**
- **Describe the exact steps to reproduce the problem**
- **Provide specific examples** (e.g., sample code that demonstrates the bug)
- **Describe the behavior you observed and what you expected to see**
- **Include relevant logs, stack traces, or screenshots**
- **Specify your environment** (OS, JDK version, Kotlin version, dependencies, etc.)

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion:

- **Use a clear and descriptive title**
- **Describe the problem your enhancement would solve**
- **Explain why this enhancement would be meaningful** to the project and its users
- **Provide specific examples of how this enhancement would be used**
- **List any alternatives you've considered**
- **Explain how the current functionality falls short**

## Pull Requests

### Process

1. Fork the repository
2. Create a new branch for your feature or bugfix (`git checkout -b feature/your-feature-name`)
3. Make your changes
4. Add or update tests as necessary
5. Run the tests to ensure all pass (`./gradlew test`)
6. Commit your changes using an appropriate commit message
7. Push to your branch (`git push origin feature/your-feature-name`)
8. Create a Pull Request against the main repository

### PR Requirements

All pull requests should:

- **Address a specific issue** or add a specific feature (create an issue first if none exists)
- **Include a problem statement** explaining what you're trying to solve and why it's meaningful
- **Include tests** that cover the new changes
- **Update documentation** if relevant
- **Pass all CI checks**
- **Be focused on a single objective** (don't mix unrelated changes)

## Development Guidelines

### Running Tests

The default test run executes the full deterministic suite:

```bash
gradle test
gradle :lirp-core:test
gradle :lirp-fx:test
gradle :lirp-sql:test
```

#### Stress-tagged tests (`Stress` tag)

Some concurrency regression tests are tagged with `Stress` and run by default with the
rest of the suite.
They are aggressive multi-iteration tripwires that protect invariants like CME-free
iteration of `ProjectionMap` / `FxProjectionMap`; they are too slow and noisy for the
short feedback loop, but valuable when modifying the affected code.

Skip them with the `kotest.tags.exclude` Gradle property:

```bash
gradle test -Pkotest.tags.exclude=Stress
gradle :lirp-core:test -Pkotest.tags.exclude=Stress
gradle :lirp-fx:test -Pkotest.tags.exclude=Stress
```

Add a new stress-tagged test by attaching the shared tag at the test definition:

```kotlin
import net.transgressoft.lirp.testing.Stress

"MyComponent stays consistent under concurrent readers and a writer"
    .config(tags = setOf(Stress)) {
        // ...
    }
```

The `Stress` tag is defined once in `lirp-core/src/test/kotlin/net/transgressoft/lirp/testing/Stress.kt`
and is visible to `lirp-fx` tests via the existing `testImplementation files(...)` wiring,
so no per-module duplication is needed.

#### Kotest parallelism

`lirp-core` enables Kotest spec-level parallelism while keeping test execution inside each
spec sequential. This reduces default wall-clock time without changing spec-local fixture
ordering. JavaFX specs remain serialized because `FxToolkitInit` and JavaFX toolkit state
are process-wide. Scheduled Stress CI is not part of this setup.

#### Event-asserting integration tests: SharedFlow collector warmup

Tests that subscribe to a repository's `SharedFlow` event stream and then immediately fire a
mutation are inherently racy: the collector coroutine launched by `subscribe { ... }` is not
necessarily fully scheduled before the test's next line runs. Without a small warmup delay
between subscribing and emitting, the very first event can be dropped and the test fails
non-deterministically. This was the root cause of the v2.5.0 SQL event-flow flake (fixed in
commit `ba7f2f2`).

**Convention:** every integration test that subscribes to a `SharedFlow` before asserting on
collected events MUST include a 50 ms warmup `delay` between the subscription and the first
mutation under test.

Required import:

```kotlin
import kotlin.time.Duration.Companion.milliseconds
```

Canonical snippet:

```kotlin
val received = AtomicReference<CrudEvent.Type?>()
repo.subscribe { event -> received.set(event.type) }
delay(50.milliseconds) // let SharedFlow collector coroutine start

repo.add(TestPerson(1).apply { firstName = "Alice" })

eventually(5.seconds) {
    received.get() shouldBe CrudEvent.Type.CREATE
}
```

Canonical call sites to mirror:

- `lirp-sql/src/integrationTest/kotlin/net/transgressoft/lirp/persistence/sql/SqlRepositoryEventIntegrationTest.kt`
  — lines 53, 73, 95, 118, 143.
- `lirp-sql/src/integrationTest/kotlin/net/transgressoft/lirp/persistence/sql/SqlRepositoryQueryDslIntegrationTest.kt`
  — line 761.

Any new integration test that asserts on `SharedFlow`-delivered events without this warmup is
considered incomplete and will be flagged in review.

### Problem Statement Requirement

When submitting a PR, always include a clear problem statement that answers:

1. What problem are you trying to solve?
2. Why is this problem meaningful to the project?
3. How does your solution address the problem?
4. What alternatives did you consider?

Example:
```
Problem: The current JsonFileRepository implementation blocks the main thread during file writes, 
causing performance issues with large datasets.

Significance: This affects applications using the repository for high-frequency data changes, 
creating UI stutters in client applications.

Solution: Implemented non-blocking file IO using coroutines to move write operations off the main thread.

Alternatives considered: 
- Thread pool approach: More complex, would require managing thread lifecycle
- Batching writes: Would introduce latency in persistence
```

## Style Guidelines

- Use the provided `.editorconfig` and Kotlin style guide settings
- Variable and function names should be descriptive and follow camelCase convention
- Keep functions focused on a single responsibility
- Add *meaningful* documentation comments to public APIs
- Use Kotlin features appropriately (extension functions, lambdas, etc.)
- Avoid unnecessary abbreviations

### Code Formatting

This project uses [ktlint](https://pinterest.github.io/ktlint/) to ensure consistent code formatting. Run this command to check for formatting issues:

```bash
./gradlew ktlintCheck
```

To automatically fix formatting issues:

```bash
./gradlew ktlintFormat
```

## Public API Compatibility (ABI)

Every published module's binary API surface is gated by the JetBrains
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator).
Each gated module commits a golden `api/<module>.api` dump describing its public/protected
ABI. `apiCheck` runs as part of `gradle check` and **fails the build whenever the compiled
surface diverges from the committed dump**. The policy is fail-on-any-change: every public-API
change must be a deliberate, reviewed `apiDump` commit rather than a silent
`NoSuchMethodError`/`AbstractMethodError` for downstream consumers.

### Layout

- **Gated modules** (each owns `api/<module>.api`): `lirp-api`, `lirp-core`, `lirp-sql-api`,
  `lirp-sql`, `lirp-fx`, `lirp-ksp` — the modules published to Maven Central.
- **Ignored** (in the root `build.gradle` `apiValidation { ignoredProjects }`): `lirp-benchmark`
  (never published) and `lirp-gradle-plugin` (its contract is the plugin id + extension, not a
  binary library API).
- **`@InternalLirpApi`** is registered as a `nonPublicMarkers` entry, so symbols annotated with
  it are excluded from the dumps even though they are `public` for cross-module use.

### Tasks

```bash
gradle apiCheck   # compare the compiled surface against the committed api/*.api dumps (wired into `check`)
gradle apiDump    # regenerate the api/*.api dumps from the current compiled surface
```

### When to regenerate the `.api` files

Re-run `apiDump` and commit the diff **whenever you intentionally change a public or protected
symbol of a gated module**:

- adding / removing / renaming a public or protected class, function, property, or constructor
- changing a public signature — parameters, return/property type, nullability, generic bounds, visibility
- adding or removing entries on a public `enum`
- a change to KSP-generated public symbols (e.g. a new `@PersistenceMapping` entity adds a
  `*_LirpTableDef` / accessor to the surface)

Run a clean compile first so KSP-generated symbols are present, then dump and re-check:

```bash
gradle clean
gradle apiDump
gradle apiCheck   # confirm green against the regenerated baseline
```

Review the `api/*.api` diff like any other source change — it is the human-readable record of
what your change does to the binary contract. A **removal or incompatible change is a breaking
change**: bump the major version and add a migration note to `CHANGELOG.md` (mirror the format of
the existing *Migration from 2.x to 3.0* section, which documents the v3.0.0 event-API break).

### When NOT to regenerate

If `apiCheck` fails and you did **not** intend an API change, do not run `apiDump` — investigate
first. An unexpected diff usually means an accidental visibility leak (a helper that should be
`internal` or `@InternalLirpApi`) or an unintended signature change. Fix the source, not the
baseline. Regenerating to make the gate green would erase the warning it exists to raise.

### Adding a new published module

Wire the module into the root `subprojects { mavenPublishing }` publishing block and run
`gradle apiDump` — a new `api/<module>.api` appears. If a module should not be gated (because it
is not published), add it to `ignoredProjects` in the root `apiValidation {}` block instead.

### Dependency note

The binary-compatibility-validator plugin is a build-classpath dependency, so its artifacts are
pinned in `gradle/verification-metadata.xml`. If you bump its version, regenerate the verification
metadata as described below.

## Supply Chain Security

### Dependency verification metadata

`gradle/verification-metadata.xml` pins SHA-256 checksums for every resolved artifact (direct and transitive, including Gradle plugins). Gradle reads this file automatically on every invocation; a checksum mismatch fails the build with `Dependency verification failed`.

**When to regenerate** — any change that alters the resolved dependency graph:

- bumping a version in `gradle/libs.versions.toml`
- adding or removing a dependency
- changing a plugin version in `build.gradle`
- upgrading the Kotlin or JVM toolchain

**How to regenerate:**

```bash
./gradlew --write-verification-metadata sha256 build cyclonedxBom --no-parallel --refresh-dependencies
```

Commit the resulting diff alongside the dependency change. Review it — only the expected artifacts should appear.

Why this exact command:

- **No `-x test`.** Excluding the `test` task skips configuring its lazily-resolved classpath, which silently drops JUnit-platform deps (`junit-bom.module`, `opentest4j.module`, etc.) from the regenerated metadata. The next CI run fails verification on those missing entries.
- **`build` covers `:lirp-sql:integrationTest`** because integration tests are wired into `check`, which `build` triggers. A separate `:lirp-sql:integrationTest` invocation is redundant.
- **`cyclonedxBom`** ensures the CycloneDX plugin's transitives are also recorded — otherwise the SBOM step on release would fail.
- **`--refresh-dependencies`** forces a fresh fetch from declared repositories rather than reusing the local cache. Without it, anything already in `~/.gradle/caches` (or in `~/.m2/` via the global init script's `mavenLocal()`) is reused, which can mask `.module` files that exist on Maven Central but aren't in the local cache.
- **`--no-parallel`** avoids `Resolution of the configuration was attempted without an exclusive lock` errors when multiple modules resolve concurrently.

**Current trust model:** `verify-metadata="true"`, `verify-signatures="false"`. PGP signature verification is deferred — it requires curating a `<trusted-keys>` block per signing identity. Checksum verification alone still defeats artifact tampering at the registry or proxy layer.

`net.transgressoft` `*-SNAPSHOT` artifacts are listed under `<trusted-artifacts>` and bypass checksum verification. SNAPSHOTs are mutable by design, so pinning their checksums is impossible — trusting the group/version pattern lets cross-project snapshot testing (via `./gradlew publishToMavenLocal`) keep working.

`*-sources.jar` and `*-javadoc.jar` are also trusted blanket-wide. IDEs (IntelliJ, Eclipse) auto-download these for code navigation via their own resolver, not through the build's task graph — so they are never seen by `--write-verification-metadata`. They are inert documentation; a compromised sources jar cannot execute code in your build. Runtime checksum pinning still applies to every executable artifact.

`org.apache.groovy` is trusted unconditionally for the same reason: IntelliJ's Gradle integration resolves Apache Groovy from Gradle's internal "Gradle Libs" repository to parse `build.gradle` DSL syntax, outside any task graph. Apache Groovy is published and signed by the ASF and is part of Gradle's own runtime — trusting the group avoids whack-a-mole when new IDE versions resolve different point versions.

**Troubleshooting:**

| Symptom | Action |
|---|---|
| `Checksum missing` on a new dep | Rerun the regeneration commands above |
| `Wrong checksum` without an intentional bump | Investigate — could be cache corruption or a compromised proxy. Do not blindly regenerate |
| Plugin resolution failure in CI | Ensure plugin's metadata covers the `pluginManagement` configurations — running `cyclonedxBom` regeneration usually picks them up |

### CycloneDX SBOM

`./gradlew cyclonedxBom --no-parallel` produces an aggregated `build/reports/cyclonedx/bom.json` (CycloneDX 1.5) that combines per-subproject BOMs at `<subproject>/build/reports/cyclonedx-direct/bom.json`. The release workflow uploads the aggregated file as a GitHub Release artifact for downstream consumers and security tools (Trivy, Grype, etc).

### Cross-checking metadata against Maven Central

PR builds run a "verification metadata cross-check" step after the main build. It re-resolves every dependency with `--refresh-dependencies` (bypassing the Gradle cache) and writes a fresh metadata report to `verification-metadata.dryrun.xml`. The committed file is then diffed against the freshly-generated one — any divergence fails the build.

This catches the failure mode that pure local regeneration can't catch: if your local Gradle cache or proxy was tampered with at metadata-generation time, the committed SHA-256 values would not match what Central actually serves. The CI cross-check has no local cache and pulls directly from Central, so a mismatch is loud and immediate.

Master builds skip this step — every metadata change has already been cross-checked at PR time, and the ~2-minute cost outweighs the marginal benefit of catching direct-push edge cases.

### Vulnerability scanning

- **PR builds** run `actions/dependency-review-action` and fail on HIGH+ severity CVEs in the PR diff.
- **Weekly schedule** runs `osv-scanner` against the full dependency graph and uploads SARIF to the GitHub Security tab.

### Renovate

`renovate.json` configures the [Renovate](https://docs.renovatebot.com) GitHub App to keep dependencies, GitHub Actions, and the Gradle wrapper up to date.

- **Schedule:** Mondays before 06:00 Europe/Madrid. CVE-flagged updates ignore the schedule.
- **Grouping:**
    - All non-plugin library bumps (entries in `libs.versions.toml` and `dependencies` blocks) are batched into a single `gradle-libraries` PR.
    - Kotlin language artifacts (plugin + stdlib/reflect/etc.) and KSP are bundled into a `kotlin` PR — they share an ABI and must move in lockstep.
    - GitHub Actions are batched into one PR (digests kept pinned via the `helpers:pinGitHubActionDigests` preset).
    - The Gradle wrapper is its own PR.
    - Every other Gradle plugin gets its own PR — plugins (e.g. cyclonedx, sonarqube) tend to ship breaking changes that need individual review.
- **GitHub Actions** are kept SHA-pinned via `pinDigests: true` — Renovate updates both the SHA and the trailing version comment.
- **Dependency Dashboard:** Renovate maintains a persistent GitHub issue listing pending updates with checkboxes. Tick a box → it opens that PR.

**Verification metadata on Renovate PRs:** Renovate does **not** automatically update `gradle/verification-metadata.xml`. Every Renovate PR that bumps a dependency will fail CI on the dependency-verification step. The workflow to merge a Renovate PR is:

1. Check out the PR branch locally.
2. Run the regeneration commands above.
3. Amend the commit and force-push to the PR branch.
4. CI re-runs and passes.

This friction is the price of strict verification. If it becomes painful, a follow-up is to add a `postUpgradeTasks` block to `renovate.json` invoking the regeneration command — but `postUpgradeTasks` requires a self-hosted Renovate instance (not the Mend cloud app), so it's a non-trivial migration.

### Pinned GitHub Actions

All `uses:` references in `.github/workflows/` are pinned to commit SHAs (with the version tag preserved in a trailing comment for human readability). Tag references like `@v4` are mutable — pinning to a SHA prevents a compromised tag from silently injecting code into CI runs.

When updating an action, look up the SHA for the target tag and update both the SHA and the comment:

```bash
gh api repos/<owner>/<repo>/git/refs/tags/<tag> --jq '.object.sha'
```

If the returned object has `type: tag` (annotated tag), dereference it: `gh api repos/<owner>/<repo>/git/tags/<sha> --jq '.object.sha'`.

## Questions?

If you have any questions or need help with the contribution process, please don't hesitate to open an issue asking for guidance.

Thank you for contributing to lirp!
