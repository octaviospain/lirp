[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/octaviospain/lirp)
![Maven Central Version](https://img.shields.io/maven-central/v/net.transgressoft/lirp-api)
![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/octaviospain/lirp/.github%2Fworkflows%2Fmaster.yml?logo=github)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=bugs)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=coverage)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)

# LIRP - Lightweight Reactive Persistence

A Kotlin/Java library where domain entities own their reactivity — property changes automatically notify subscribers, repositories persist transparently.

## Overview

lirp is built around a single idea: **domain entities should not be passive data containers**. Inspired by Domain-Driven Design, entities in lirp carry identity and behavior, and they publish their own state changes without external wiring.

Unlike general-purpose reactive toolkits (Kotlin Flow, RxJava, EventBus) where you wire streams yourself, lirp operates at the domain level — **your domain objects _are_ the reactive infrastructure**. A property declared with `reactiveProperty()` automatically notifies every subscriber on assignment, with zero overhead for unobserved entities thanks to lazy publisher initialization.

Repositories are event publishers too: they emit `CrudEvent`s on add/remove and serve as entity factories through typed `create()` methods. JSON repositories add automatic, debounced persistence — no manual save calls needed.

Built on Kotlin Coroutines and Kotlin Serialization. Targets **JVM 17+, Kotlin 2.3.10**.

## Quick Example

```kotlin
data class Product(override val id: Int, var name: String, initialPrice: Double = 0.0) : ReactiveEntityBase<Int, Product>() {
    var price: Double by reactiveProperty(initialPrice)

    override val uniqueId = "product-$id"
    override fun clone() = Product(id, name, price)
}

val product = Product(1, "Widget")

product.subscribe { event ->
    println("${event.oldEntity.price} -> ${event.newEntity.price}")
}

product.price = 29.99  // prints: 0.0 -> 29.99
```

No event bus. No manual Flow collection. The entity notifies its subscribers directly.

## Repository as Factory

Repositories manage entity lifecycles and register automatically via `@LirpRepository`:

```kotlin
@LirpRepository
class ProductRepository(name: String = "Products") : VolatileRepository<Int, Product>(name) {
    fun create(id: Int, name: String, price: Double = 0.0): Product {
        val product = Product(id, name, price)
        add(product)
        return product
    }
}

val repo = ProductRepository()
val widget = repo.create(1, "Widget", 29.99)

repo.subscribe(CrudEvent.Type.CREATE) { event ->
    println("Added: ${event.entities.values}")
}
```

## Secondary Indexes

Annotate properties with `@Indexed` for O(1) equality lookups — no collection scan:

```kotlin
data class Product(
    override val id: Int,
    var name: String,
    @Indexed(name = "cat") val category: String
) : ReactiveEntityBase<Int, Product>() {
    // ...
}

val electronics: Set<Product> = repo.findByIndex("category", "electronics")
val bySku: Optional<Product> = repo.findFirstByIndex("sku", "SKU-001")
```

## Installation

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("net.transgressoft:lirp-api:<version>")
    implementation("net.transgressoft:lirp-core:<version>")
    ksp("net.transgressoft:lirp-ksp:<version>")
}
```

**Requirements:** JVM 17+, Kotlin 2.3.10

The KSP plugin enables `@LirpRepository` and `@ReactiveEntityRef` annotations for zero-config repository registration and aggregate reference wiring.

## Persistence Hierarchy

lirp provides a layered persistence abstraction:

```
Repository (interface, lirp-api)
  └── PersistentRepository (interface, lirp-api)       — marker for durable backends
        └── JsonRepository (interface, lirp-api)        — JSON-specific contract

VolatileRepository (class, lirp-core)                   — in-memory
  └── PersistentRepositoryBase (abstract, lirp-core)    — subscription mgmt, lifecycle, dirty tracking
        └── JsonFileRepository (class, lirp-core)       — debounced JSON file writes
```

`PersistentRepository` is the marker interface for repositories that survive JVM lifetime. `PersistentRepositoryBase` provides the shared foundation for all durable backends: it auto-subscribes entities on add, cancels subscriptions on remove, guards mutating operations after close, and calls `onDirty()` when the state changes. Subclasses implement `onDirty()` to trigger their storage mechanism — `JsonFileRepository` sends to its serialization channel, a future `SqlRepository` would schedule a DB write.

## Key Features

- Entity-first reactivity with `reactiveProperty()` — declare an observable property with `var x by reactiveProperty(init)` and assignment automatically notifies subscribers
- Two subscription patterns: repository-level (`CrudEvent`) and entity-level (`MutationEvent`)
- DDD aggregate references with `@ReactiveEntityRef` and configurable cascade (DETACH/CASCADE/RESTRICT)
- Repository-as-factory pattern with typed `create()` methods
- Layered persistence abstraction: `PersistentRepository` / `PersistentRepositoryBase` as extensible foundation for JSON and future SQL backends
- Automatic JSON persistence with debounced writes via `JsonFileRepository`
- Secondary indexes for O(1) lookups via `@Indexed`
- Full Java interoperability
- KSP-powered zero-config registration via `@LirpRepository`

## Documentation

For detailed guides, examples, and architecture diagrams, see the **[LIRP Wiki](https://github.com/octaviospain/lirp/wiki)**.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License and Attributions

Copyright (c) 2025 Octavio Calleya García.

lirp is free software under GNU GPL version 3 license and is available [here](https://www.gnu.org/licenses/gpl-3.0.en.html#license-text).

This project uses:
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) for asynchronous programming
- [Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON processing
- [Kotest](https://kotest.io/) for testing

The approach is inspired by books including [Object Thinking by David West](https://www.goodreads.com/book/show/43940.Object_Thinking), [Domain-Driven Design: Aligning Software Architecture and Business Strategy by Vladik Khonon](https://www.goodreads.com/book/show/57573212-learning-domain-driven-design) and [Elegant Objects by Yegor Bugayenko](https://www.yegor256.com/elegant-objects.html).
