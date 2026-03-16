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

A reactive library for Kotlin & Java projects that implements the Publisher-Subscriber pattern, enabling more maintainable and decoupled systems through reactive programming principles.

## 📖 Overview

lirp embraces the core principle of [Domain-Driven Design](https://en.wikipedia.org/wiki/Domain-driven_design): **domain entities are not passive data containers — they are the central building blocks of your system**, carrying identity, behavior, and the responsibility to communicate their own state changes.

In many codebases, domain objects end up as anemic structures shuffled between services that externally query and mutate them. lirp takes the opposite approach. Inspired by DDD's concept of _rich domain models_, entities in lirp **own their reactivity**. When a property changes, the entity itself publishes the event — no external event bus wiring, no service-layer glue. Subscribers observe the entity directly, preserving the [Bounded Context](https://martinfowler.com/bliki/BoundedContext.html) boundary and keeping coupling low.

This maps naturally to DDD building blocks:
- **Entities** (`ReactiveEntity`) carry identity and emit mutation events when their state changes
- **Repositories** (`Repository`, `JsonRepository`) manage entity lifecycle and publish collection-level CRUD events
- **Domain Events** (`CrudEvent`, `MutationEvent`) are first-class citizens, not an afterthought bolted onto a service layer

The result is a system where the domain model is inherently observable and self-describing — exactly what DDD prescribes.

### What Makes lirp Different?

Libraries like [Kotlin Flow](https://github.com/Kotlin/kotlinx.coroutines), [RxJava](https://github.com/ReactiveX/RxJava), and [Guava EventBus](https://github.com/google/guava/wiki/EventBusExplained) are powerful general-purpose reactive toolkits. You wire streams, define operators, and connect publishers to subscribers yourself. lirp operates at a higher level of abstraction: **your domain objects _are_ the reactive infrastructure**.

```kotlin
data class Product(override val id: Int, var name: String) : ReactiveEntityBase<Int, Product>() {
    var price: Double = 0.0
        set(value) { mutateAndPublish(value, field) { field = it } }

    override val uniqueId = "product-$id"
    override fun clone() = copy()
}

val product = Product(1, "Widget")

// Subscribe directly to the entity — no bus, no stream setup
product.subscribe { event ->
    println("${event.oldEntity.price} → ${event.newEntity.price}")
}

product.price = 29.99  // prints: 0.0 → 29.99
```

No event bus registration. No manual Flow collection. The entity publishes its own changes, and subscribers react — that's it.

**Where lirp stands apart:**

*   **Entity-First Reactivity:** Domain objects are inherently reactive. A simple `mutateAndPublish` in a property setter is all it takes — the entity notifies its subscribers automatically, with zero-overhead lazy publisher initialization for unobserved entities.
*   **Automated Persistence:** `JsonRepository` provides automatic, thread-safe, debounced JSON serialization. Add an entity to a repository and it persists to disk. Change a property and the file updates. No manual save calls.
*   **Two Granularities, One Pattern:** Observe an entire collection (repository-level CRUD events) or a single entity instance (property mutation events) using the same `subscribe` API.
*   **DDD-Aligned Design:** Entities carry identity and behavior. Repositories manage lifecycle. Events are domain concepts. The API naturally guides you toward a rich domain model rather than an anemic one.

Built on [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization), lirp provides a ready-to-use foundation for reactive, persistent applications with clean domain boundaries.

**Requirements:**
* Java 21+
* Kotlin 2.2.10

**Key Features:**

* **Event-Driven Architecture:** Built around the Publisher-Subscriber pattern for loosely coupled communication
* **Reactive Entities:** Objects that automatically notify subscribers when their state changes
* **Automated JSON Serialization:** Repository implementations that persist entity changes to JSON files automatically
* **Thread-Safe Operations:** Concurrent operations are handled safely with debounced file I/O
* **Repository Pattern:** Flexible data access through repositories with powerful querying capabilities
* **Reactive Primitives:** Wrapper types that make primitive values observable
* **Asynchronous Processing:** Non-blocking operations using Kotlin coroutines
* **Java Interoperability:** Designed to work seamlessly from both Kotlin and Java code

## 📑 Table of Contents

- [Core Concepts: Reactive Event System](#-core-concepts-reactive-event-system)
- [Core Concepts: JSON Serialization](#-core-concepts-json-serialization)
- [Java Interoperability](#java-interoperability)
- [Contributing](#-contributing)
- [License and Attributions](#-license-and-attributions)

## 🔄 Core Concepts: Reactive Event System

The heart of lirp is its reactive event system, where objects communicate through events rather than direct manipulation.

### Reactive Primitives

The simplest way to understand the reactive approach is through the primitive wrappers. These allow basic values to participate in the reactive system:

```kotlin
// Create a reactive primitive with an ID and initial value
val appName: ReactivePrimitive<String> = ReactiveString("MyApp")

// Subscribe to changes with a simple lambda function
val subscription = appName.subscribe { event ->
    val oldValue = event.oldEntities.values.first().value
    val newValue = event.entities.values.first().value
    println("Config changed: $oldValue -> $newValue")
}

// When value changes, subscribers are automatically notified
appName.value = "NewAppName"  
// Output: Config changed: MyApp -> NewAppName

// Later, if needed, you can cancel the subscription
subscription.cancel()
```

### Reactive Entities

Any object can become reactive by implementing the `ReactiveEntity` interface, typically by extending `ReactiveEntityBase`:

```kotlin
// Define a reactive entity
data class Person(override val id: Int, var name: String) : ReactiveEntityBase<Int, Person>() {
    var salary: Double = 0.0
        set(value) {
            // mutateAndPublish handles the notification logic
            mutateAndPublish(value, field) { field = it }
        }

    override val uniqueId: String = "person-$id"
    override fun clone(): Person = copy()
}
```

### Two Subscription Patterns

The library provides **two distinct ways** to observe changes, each optimized for different use cases:

#### 1. Repository-Level Subscriptions (Collection Changes)

**Use this when:** You want to observe all entities of a type - additions, removals, or any entity modifications in the collection.

**Best for:** Dashboards, search indexers, cache invalidators, audit logs, UI lists showing "all items".

```kotlin
val repository: Repository<Int, Person> = VolatileRepository("PersonRepository")

// Subscribe to CRUD events - fires for ANY entity in the collection
repository.subscribe(CrudEvent.Type.CREATE) { event ->
    println("New persons added: ${event.entities.values}")
}

repository.subscribe(CrudEvent.Type.UPDATE) { event ->
    println("Persons modified: ${event.entities.values}")
}

repository.subscribe(CrudEvent.Type.DELETE) { event ->
    println("Persons removed: ${event.entities.values}")
}

// Adding entities triggers CREATE events
repository.add(Person(1, "Alice"))
// Output: New persons added: [Person(id=1, name=Alice)]

// Modifying through repository triggers UPDATE events
repository.runForSingle(1) { person ->
    person.salary = 80000.0
}
// Output: Persons modified: [Person(id=1, name=Alice, salary=80000.0)]
```

**Memory efficiency:** Repository publishers are created once per collection, regardless of entity count. Observing 10,000 entities requires only **one subscription**.

#### 2. Entity-Level Subscriptions (Specific Entity Mutations)

**Use this when:** You want to observe a specific entity instance – only its property changes.

**Best for:** Detail views, form bindings, real-time updates for a specific item, entity-specific validations.

```kotlin
val person = Person(1, "Alice")

// Subscribe to THIS SPECIFIC person's property changes
val subscription = person.subscribe { event ->
    val newEntity = event.newEntity
    val oldEntity = event.oldEntity
    println("Person ${newEntity.id} changed: ${oldEntity.salary} → ${newEntity.salary}")
}

// Direct property changes trigger notifications
person.salary = 75000.0
// Output: Person 1 changed: 0.0 → 75000.0

// Unsubscribe when no longer needed
subscription.cancel()
```

**Memory efficiency:** Entity publishers use **lazy initialization** – they're only created when someone subscribes. Entities without subscribers have zero reactive overhead.

### Extensibility

The library is designed to be extensible, allowing you to create custom publishers and subscribers:

1. **Custom Publishers:** Implement `LirpEventPublisher<E>` or extend `LirpEventPublisherBase<E>` to create new event sources
2. **Custom Subscribers:** Implement `LirpEventSubscriber<T, E>` or extend `TransEventSubscriberBase<T, E>` to handle events
3. **Custom Events:** Create new event types by implementing the `TransEvent` interface

For Java compatibility or more complex subscription handling, you can also implement a full subscriber:

```kotlin
// Create a subscriber with more control over lifecycle events
val repositorySubscriber: TransEventSubscriber<Person, CrudEvent<Int, Person>> = 
    object : TransEventSubscriberBase<Person, CrudEvent<Int, Person>>("RepositorySubscriber") {
        init {
            // Set up subscription actions
            addOnNextEventAction(StandardCrudEvent.Type.CREATE) { event ->
                println("Entities created: ${event.entities.values}")
            }
            
            addOnErrorEventAction { error ->
                println("Error occurred: $error")
            }
            
            addOnCompleteEventAction {
                println("Publisher has completed sending events")
            }
        }
    }

// Subscribe using the full subscriber
repository.subscribe(repositorySubscriber)
```

The core API classes that library consumers will typically use:

- `TransEventPublisher` - Interface for objects that publish events
- `TransEventSubscriber` - Interface for objects that subscribe to events
- `ReactiveEntity` - Interface for entities that can be observed
- `Repository` - Interface for collections of entities with CRUD operations
- `CrudEvent` - Events representing repository operations
- `JsonRepository` - Interface for repositories with JSON persistence

## 💾 Core Concepts: JSON Serialization

lirp provides automatic JSON serialization for repository operations, making persistence seamless. The library uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON processing, so users should familiarize themselves with this library to effectively create serializers for their entity types.

### JSON File Repositories

The library includes implementations that automatically persist entities to JSON files:

```kotlin
// Define a serializer for your entity type using kotlinx.serialization
@Serializable
data class Person(val id: Int, val name: String, var salary: Double = 0.0) : ReactiveEntityBase<Int, Person>() {
    override val uniqueId: String = "person-$id"
    override fun clone(): Person = copy()
}

// Create a map serializer for your entities
object MapIntPersonSerializer : KSerializer<Map<Int, Person>> {
    // Serialization implementation details
}

// Define your repository class
class PersonJsonFileRepository(file: File) : JsonFileRepository<Int, Person>(
    name = "PersonRepository",
    file = file,
    mapSerializer = MapIntPersonSerializer
)

// Create and use the repository
val jsonRepository: JsonRepository<Int, Person> = PersonJsonFileRepository(File("persons.json"))
jsonRepository.add(Person(1, "Alice"))
jsonRepository.add(Person(2, "Bob"))

// When entities change, the JSON file is automatically updated
jsonRepository.runForSingle(1) { person ->
    person.salary = 85000.0
}

// Changes are debounced to prevent excessive file operations
```

### Flexible JSON Repository

For simpler use cases, the library provides a flexible repository for primitive values:

```kotlin
// Create a repository for configuration values
val configRepository  = FlexibleJsonFileRepository(File("config.json"))

// Create reactive primitives in the repository
val serverName: ReactivePrimitive<String> = configRepository.createReactiveString("server.name", "MainServer")
val maxConnections: ReactivePrimitive<Int> = configRepository.createReactiveInt("max.connections", 100)
val debugMode: ReactivePrimitive<Boolean> = configRepository.createReactiveBoolean("debug.mode", false)

// When values change, they are automatically persisted
maxConnections.value = 150
debugMode.value = true
// The JSON file is updated with the new values
```

### Key Benefits of Automatic Serialization

1. **Transparent Persistence** - No need to manually save changes
2. **Optimized I/O** - Changes are debounced to reduce disk operations
3. **Thread Safety** - Concurrent operations are handled safely
4. **Consistency** - Repository and file are always in sync

### Java Interoperability

lirp is designed to work seamlessly from both Kotlin and Java code. Below are examples demonstrating how to use the library from Java:

#### 1. Working with Reactive Primitives in Java

```java
// Create a reactive primitive with an ID and initial value
var appName = new ReactiveString("app.name", "MyApp");

// Subscribe to changes
var subscription = appName.subscribe(event -> {
    var oldValue = event.getOldEntities().values().iterator().next().getValue();
    var newValue = event.getEntities().values().iterator().next().getValue();
    System.out.println("Config changed: " + oldValue + " -> " + newValue);
});

// When value changes, subscribers are automatically notified
appName.setValue("NewAppName");
// Output: Config changed: MyApp -> NewAppName

// Later, if needed, you can cancel the subscription
subscription.cancel();
```

#### 2. Repository-Level Subscriptions in Java (All Entities)

```java
// Create a repository for Person entities
var repository = new VolatileRepository<Integer, Person>("PersonRepository");

// Subscribe to observe ALL persons in the collection
var subscription = repository.subscribe(CrudEvent.Type.UPDATE, event -> {
    System.out.println("Persons modified: " + event.getEntities().values());
});

// Repository operations trigger events
repository.add(new Person(1, "Alice", 0L, true));

// Modify through repository - fires UPDATE event
repository.runForSingle(1, person -> person.setName("John"));
// Output: Persons modified: [Person(id=1, name=John, money=0, morals=true)]

subscription.cancel();
```

#### 3. Entity-Level Subscriptions in Java (Specific Entity)

```java
// Get or create a specific person
var person = new Person(1, "Alice", 0L, true);

// Subscribe to THIS SPECIFIC person's changes
var subscription = person.subscribe(event -> {
    var newPerson = event.getNewEntity();
    var oldPerson = event.getOldEntity();
    System.out.println("Person " + newPerson.getId() + " changed: " +
                       oldPerson.getName() + " → " + newPerson.getName());
});

// Direct property changes trigger notifications
person.setName("John");
// Output: Person 1 changed: Alice → John

subscription.cancel();
```

#### 4. Working with Flexible JSON Repository in Java

```java
// Create a JSON file repository
var configFile = new File("config.json");
var configRepository = new FlexibleJsonFileRepository(configFile);

// Create reactive primitives in the repository
var serverName = configRepository.createReactiveString("server.name", "MainServer");
var maxConnections = configRepository.createReactiveInt("max.connections", 100);
var debugMode = configRepository.createReactiveBoolean("debug.mode", false);

// When values change, they are automatically persisted
maxConnections.setValue(150);
debugMode.setValue(true);
serverName.setValue("BackupServer");

// Close to ensure all changes are written
configRepository.close();

// Changes persist across repository instances
var reloadedRepo = new FlexibleJsonFileRepository(configFile);
// Values remain: serverName="BackupServer", maxConnections=150, debugMode=true
```

For complete working examples, see [JavaInteroperabilityTest.java](https://github.com/octaviospain/lirp/blob/master/lirp-core/src/test/java/net/transgressoft/lirp/JavaInteroperabilityTest.java) in the repository.

## 🤝 Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## 📄 License and Attributions

Copyright (c) 2025 Octavio Calleya García.

lirp is free software under GNU GPL version 3 license and is available [here](https://www.gnu.org/licenses/gpl-3.0.en.html#license-text).

This project uses:
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) for asynchronous programming
- [Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON processing
- [Kotest](https://kotest.io/) for testing

The approach is inspired by books including  [Object Thinking by David West](https://www.goodreads.com/book/show/43940.Object_Thinking), [Domain-Driven Design: Aligning Software Architecture and Business Strategy by Vladik Khonon](https://www.goodreads.com/book/show/57573212-learning-domain-driven-design) and [Elegant Objects by Yegor Bugayenko](https://www.yegor256.com/elegant-objects.html).
