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
./gradlew test                    # full project
./gradlew :lirp-core:test         # single module
```

#### Opt-in stress tests (`Stress` tag)

Some concurrency regression tests are tagged with `Stress` and **excluded by default**.
They are aggressive multi-iteration tripwires that protect invariants like CME-free
iteration of `ProjectionMap` / `FxProjectionMap`; they are too slow and noisy for the
default loop but valuable when modifying the affected code.

Include them with the `kotest.tags.include` Gradle property:

```bash
./gradlew test -Pkotest.tags.include=Stress
./gradlew :lirp-core:test -Pkotest.tags.include=Stress
./gradlew :lirp-fx:test -Pkotest.tags.include=Stress
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

## Questions?

If you have any questions or need help with the contribution process, please don't hesitate to open an issue asking for guidance.

Thank you for contributing to lirp!