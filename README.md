# Velr

Velr is an embedded property-graph database from Velr.ai, written in Rust,
built on top of SQLite (persisting to a standard SQLite database file) and
queried using the openCypher language.

It runs in-process and is designed for local, embedded, and edge use cases.

This package provides the **Kotlin bindings** for Velr on JVM and Android. It wraps a bundled native runtime through a Rust JNI bridge and exposes an idiomatic API for executing Cypher queries, streaming result tables, working with transactions and savepoints, binding parameters, registering vector embedders, inspecting migrations, and working with Arrow C Data and Arrow IPC.

The Kotlin artifact includes the Java core classes plus Kotlin extension helpers.

For the main Velr public entry point, see [velr-ai/velr](https://github.com/velr-ai/velr).
For the Velr website, see [velr.ai](https://velr.ai/).

## Community

- **Community and questions:** [GitHub Discussions](https://github.com/velr-ai/velr/discussions)
- **Bug reports and feature requests:** [GitHub Issues](https://github.com/velr-ai/velr/issues)
- **Kotlin examples:** [velr-kotlin-examples](https://github.com/velr-ai/velr-kotlin-examples)

We would love to have you join the Velr community.

---

## Installation

Add the published driver artifact to your project.

### JVM

Gradle:

```kotlin
dependencies {
    implementation("ai.velr:velr-kotlin-driver:VERSION")
}
```

Maven:

```xml
<dependency>
    <groupId>ai.velr</groupId>
    <artifactId>velr-kotlin-driver</artifactId>
    <version>VERSION</version>
</dependency>
```

### Android

Gradle:

```kotlin
dependencies {
    implementation("ai.velr:velr-kotlin-driver-android:VERSION")
}
```

Maven:

```xml
<dependency>
    <groupId>ai.velr</groupId>
    <artifactId>velr-kotlin-driver-android</artifactId>
    <version>VERSION</version>
    <type>aar</type>
</dependency>
```

The JVM artifact ships native libraries for supported desktop platforms. The
Android artifact is an AAR that packages ABI-specific `libvelr_jni.so` files.
Applications install the Maven artifact and use the API directly; no separate
native runtime package or engine download is required.

### Licensing in simple terms

* The **Kotlin binding source code** in this package is licensed under **MIT**.
* The **bundled native runtime binaries** may be **used and freely redistributed in unmodified form** under the terms of **`LICENSE.runtime`**.

---

## Quick start

```kotlin
import ai.velr.velr
import ai.velr.toObjectRows

velr().use { db ->
    db.run("CREATE (:Person {name:'Keanu Reeves', born:1964})")
    db.execOne("MATCH (p:Person) RETURN p.name AS name, p.born AS born").use { table ->
        println(table.columnNames())
        println(table.toObjectRows())
    }
}
```

Open a file-backed database instead of an in-memory database:

```kotlin
import ai.velr.velr

velr("mygraph.db").use { db ->
    db.run("CREATE (:Person {name:'Alice'})")
}
```

Open an existing database for reads only:

```kotlin
import ai.velr.Velr

Velr.openReadonly("mygraph.db").use { db ->
    val rows = db.query("MATCH (n) RETURN count(n) AS count")
    println(rows)
}
```

`openReadonly()` never creates, initializes, migrates, or repairs a database.
The file must already exist and have a supported Velr schema version. Older
supported databases remain available for reads. Writes and features that
require the current schema fail with a normal query error until the database is
explicitly migrated.

---

## Schema migration

Velr does not migrate supported older databases automatically on open. Use the
driver migration API, or run `MIGRATE DATABASE`, from maintenance code when you
intend to update the on-disk schema.

```kotlin
import ai.velr.velr

velr("mygraph.db").use { db ->
    if (db.needsMigration()) {
        val report = db.migrate()
        println("${report.status()} ${report.fromVersion()} -> ${report.toVersion()}")
    }
}
```

The equivalent Cypher command is useful for scripts and tools that already work
through query execution:

```cypher
MIGRATE DATABASE
```

---

## Introspection

Use `SHOW CURRENT GRAPH SHAPE` to inspect the observed schema of the graph. It
reports the shape present in stored data: node labels, relationship types,
properties, observed value types, and counts. It is an observed shape surface,
not a declared GQL graph type.

```kotlin
velr("mygraph.db").use { db ->
    db.execOne(
        """
        SHOW CURRENT GRAPH SHAPE
        YIELD element_kind, element_name, property_name, observed_type, owner_count
        WHERE element_kind = 'node_property'
        RETURN element_name, property_name, observed_type, owner_count
        """
    ).use { table ->
        println(table.toObjectRows())
    }
}
```

Use `YIELD` to compose the command with `WHERE` and `RETURN`. Plain
`SHOW CURRENT GRAPH SHAPE` returns the default projection; `YIELD *` exposes the
full current row shape.

---

## Fulltext Search

Fulltext search is available through normal Cypher execution. Define indexes
with `CREATE FULLTEXT INDEX` and query them with
`CALL db.index.fulltext.queryNodes(...)`.

```kotlin
velr("mygraph.db").use { db ->
    db.run(
        """
        CREATE FULLTEXT INDEX paperText
        FOR (n:Paper) ON EACH [n.title, n.abstract]
        """
    )

    db.execOne(
        """
        CALL db.index.fulltext.queryNodes('paperText', 'abstract:vector')
        YIELD node, score
        RETURN node, score
        """
    ).use { table ->
        println(table.toObjectRows())
    }
}
```

Fulltext indexes use a sidecar next to file-backed databases. The sidecar is
kept up to date by writes and rebuilt on open if it is missing or corrupt.

---

## Vector Search

Register an embedding callback, then reference it from `CREATE VECTOR INDEX`.
Velr invokes the callback for index maintenance when indexed source values
change and for text queries passed to `CALL db.index.vector.queryNodes(...)`.

```kotlin
import ai.velr.VelrValueType
import ai.velr.VectorEmbeddingPurpose
import ai.velr.velr

fun embedText(text: String, dimensions: Int): FloatArray {
    // Call your embedding model here.
    return FloatArray(dimensions)
}

velr("mygraph.db").use { db ->
    db.registerVectorEmbedder("text") { inputs ->
        Array(inputs.size) { i ->
            val input = inputs[i]
            val text = input.fields()
                .joinToString("\n") { field ->
                    val value = field.velrValue()
                    when (value.type()) {
                        VelrValueType.STRING -> value.asString()
                        else -> value.display()
                    }
                }
            val prefix = if (input.purpose() == VectorEmbeddingPurpose.QUERY) "query: " else "passage: "
            embedText(prefix + text, input.dimensions())
        }
    }

    db.run(
        """
        CREATE VECTOR INDEX paperEmbedding IF NOT EXISTS
        FOR (n:Paper)
        ON EACH [n.title, n.abstract, n.published, n.tags]
        OPTIONS { indexConfig: { dimensions: 384, metric: 'cosine', embedder: 'text' } }
        """
    )

    db.execOne(
        """
        CALL db.index.vector.queryNodes('paperEmbedding', 10, 'paper about greek letters')
        YIELD node, score
        RETURN node, score
        """
    ).use { table ->
        println(table.toObjectRows())
    }
}
```

The callback must return one finite `FloatArray` per input, with
exactly the dimension count requested by the index.

`ON EACH [n.title, n.abstract, n.published, n.tags]` passes property values to
the callback in that order. Query text is passed as one unnamed string field.

`VectorEmbeddingInput` exposes the index name, dimensions, purpose, entity
kind, entity id, and selected fields. `VectorEmbeddingField.velrValue()` returns
a typed `VelrValue` for the source property. It covers null, bool, int64,
double, string, date, local time, zoned time, local datetime, zoned datetime,
duration, point, geometry, geography, list, vector, and bytes. Scalars have
direct Java/Kotlin accessors; temporal values expose canonical text; spatial
values expose GeoJSON; list and vector values expose canonical JSON; byte values
expose copied byte arrays. The value also exposes the storage kind, canonical
JSON rendering, display text, and raw TEXT/BLOB storage bytes when available.
`VectorEmbeddingInput.text()` joins field display strings for simple local or
toy embedders.

Vector `score` is metric-dependent and non-normalized; higher scores are better
within a single query result set. Vector indexes use a sidecar next to
file-backed databases.

---

## Query model

A query may produce zero or more result tables.

Velr exposes three main ways to run Cypher:

* `run()` executes a query or script and drains all result tables.
* `exec()` returns a stream of result tables.
* `execOne()` expects exactly one result table.

Use `exec()` when a query or script may produce multiple result tables. Tables
pulled from `exec()` are stream-scoped and remain valid while the stream is
open. Tables returned by `execOne()` are parent-scoped and remain valid until
the owning connection or transaction closes, or until the table is closed.

### Query parameter binding

Use `QueryOptions` to bind openCypher parameters out of band. Query text uses
`$name`; parameter names in host code omit the leading `$`. Values are passed as
Cypher values, not interpolated into query text.

```kotlin
val options = QueryOptions.builder()
    .param("name", "Alice")
    .param("minAge", 18L)
    .maxResultRows(10)
    .build()

velr().use { db ->
    db.execOne(
        "MATCH (p:Person) WHERE p.name = ${'$'}name AND p.age >= ${'$'}minAge RETURN p",
        options,
    ).use { table ->
        println(table.toObjectRows())
    }
}
```

`maxResultRows` caps rows returned by each result table. Existing Cypher
`LIMIT` clauses still apply.

---

## Transactions and savepoints

Use `beginTx()` to open a transaction. Closing a transaction without `commit()`
rolls it back.

```kotlin
velr("mygraph.db").use { db ->
    db.transaction { tx ->
        tx.run("CREATE (:Movie {title:'Interstellar', released:2014})")
    }
}
```

Velr supports two savepoint styles:

* `savepoint()` creates a scoped, handle-owned savepoint.
* `savepointNamed(name)` creates a transaction-owned named savepoint.

`rollbackTo(name)` rolls back to a named savepoint, discards newer named
savepoints, and keeps the target named savepoint active.
`releaseSavepoint(name)` releases a named savepoint by name.

---

## Arrow

Velr can bind live Arrow C Data Interface columns under a logical name and
query them from Cypher with `UNWIND BIND(...)`.

```kotlin
import ai.velr.arrowColumn
import ai.velr.velr
import org.apache.arrow.c.ArrowArray
import org.apache.arrow.c.ArrowSchema
import org.apache.arrow.c.Data
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector

RootAllocator(Long.MAX_VALUE).use { allocator ->
    velr().use { db ->
        BigIntVector("id", allocator).use { id ->
            ArrowArray.allocateNew(allocator).use { array ->
                ArrowSchema.allocateNew(allocator).use { schema ->
                    id.allocateNew(3)
                    id.setSafe(0, 1L)
                    id.setSafe(1, 2L)
                    id.setSafe(2, 3L)
                    id.valueCount = 3

                    Data.exportVector(allocator, id, null, array, schema)
                    try {
                        db.bindArrow("_ids", arrowColumn("id", schema.memoryAddress(), array.memoryAddress()))
                    } finally {
                        schema.release()
                    }
                }
            }
        }

        db.execOne("UNWIND BIND('_ids') AS row RETURN row.id AS id ORDER BY id").use { table ->
            println(table.toObjectRows())
        }
    }
}
```

`bindArrow()` accepts single-chunk `ArrowColumn` values built from
`ArrowSchema` and `ArrowArray` struct addresses. `bindArrowChunks()` accepts
one or more chunks per column using `ArrowColumn.chunks(...)`. The schema is
borrowed for the duration of the call. The array payload is transferred to Velr
by the call; after calling, close wrapper objects that own the struct memory,
but do not call the `ArrowArray` release callback.

Velr can also export result tables as Arrow IPC file bytes and bind Arrow IPC
input under a logical name.

```kotlin
velr().use { db ->
    val ipc = db.execOne("UNWIND [1,2,3] AS id RETURN id AS id ORDER BY id").use { table ->
        table.toArrowIpc()
    }

    db.bindArrowIpc("_ids", ipc)
    db.execOne("UNWIND BIND('_ids') AS row RETURN row.id AS id ORDER BY id").use { table ->
        println(table.toObjectRows())
    }
}
```

`bindArrowIpc()` accepts Arrow IPC file / Feather v2 bytes and borrows the
provided byte array for the duration of the call. Arrow C Data and Arrow IPC
binding are available on both database connections and transactions.

---

## Explain support

Use `explain()` and `explainAnalyze()` to inspect query plans.

```kotlin
velr().use { db ->
    db.explain("MATCH (p:Person) RETURN p.name AS name").use { trace ->
        println(trace.compact())
    }
}
```

---

## Query language support

Velr supports the openCypher query language and passes all positive openCypher
TCK tests. Exact error semantics are not guaranteed to match other openCypher
implementations.

Fulltext search and vector search are available through Cypher DDL and `CALL`
syntax.

---

## Thread safety

Velr connections and active result handles are not safe for concurrent use from
multiple threads. Open one connection per thread and do not share active
connections, transactions, streams, tables, row iterators, savepoints, or
explain traces across threads.

The API is synchronous. Applications that need scheduling can run database work
through standard JVM executors, Android dispatchers, or Kotlin coroutines while
preserving the ownership rules above.

---

## License

See [`LICENSE`](LICENSE) and [`LICENSE.runtime`](LICENSE.runtime).
