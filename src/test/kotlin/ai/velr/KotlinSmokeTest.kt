package ai.velr

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

fun main() {
    kotlinVelrValueApi()
    velr().use { db ->
        kotlinRoundTrip(db)
        kotlinTransactionHelper(db)
        kotlinSavepointHelper(db)
        kotlinRowsAndCells(db)
        kotlinQueryOptions(db)
    }
    kotlinVectorSmoke()
}

private fun kotlinVelrValueApi() {
    val values = listOf(
        VelrValue.nullValue(),
        VelrValue.bool(true),
        VelrValue.int64(42L),
        VelrValue.doubleValue(3.5),
        VelrValue.string("Ada"),
        VelrValue.canonical(VelrValueType.DATE, VelrStorageType.BLOB, "\"2024-05-01\"", "2024-05-01", byteArrayOf(1)),
        VelrValue.canonical(VelrValueType.LOCAL_TIME, VelrStorageType.BLOB, "\"10:35:00\"", "10:35:00", byteArrayOf(2)),
        VelrValue.canonical(VelrValueType.ZONED_TIME, VelrStorageType.BLOB, "\"10:35:00+01:00\"", "10:35:00+01:00", byteArrayOf(3)),
        VelrValue.canonical(
            VelrValueType.LOCAL_DATETIME,
            VelrStorageType.BLOB,
            "\"2024-05-01T10:35:00\"",
            "2024-05-01T10:35:00",
            byteArrayOf(4),
        ),
        VelrValue.canonical(
            VelrValueType.ZONED_DATETIME,
            VelrStorageType.BLOB,
            "\"2024-05-01T10:35:00+01:00\"",
            "2024-05-01T10:35:00+01:00",
            byteArrayOf(5),
        ),
        VelrValue.canonical(VelrValueType.DURATION, VelrStorageType.BLOB, "\"P1DT2H\"", "P1DT2H", byteArrayOf(6)),
        VelrValue.canonical(
            VelrValueType.POINT,
            VelrStorageType.BLOB,
            "{\"type\":\"Point\",\"coordinates\":[1,2]}",
            "POINT(1 2)",
            byteArrayOf(7),
        ),
        VelrValue.canonical(
            VelrValueType.GEOMETRY,
            VelrStorageType.BLOB,
            "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}",
            "LINESTRING(0 0,1 1)",
            byteArrayOf(8),
        ),
        VelrValue.canonical(
            VelrValueType.GEOGRAPHY,
            VelrStorageType.BLOB,
            "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,0]]]}",
            "POLYGON((0 0,1 0,1 1,0 0))",
            byteArrayOf(9),
        ),
        VelrValue.canonical(VelrValueType.LIST, VelrStorageType.BLOB, "[1,2]", "[1,2]", byteArrayOf(10)),
        VelrValue.canonical(VelrValueType.VECTOR, VelrStorageType.BLOB, "[0.1,0.2]", "[0.1,0.2]", byteArrayOf(11)),
        VelrValue.bytes(byteArrayOf(1, 2, 3), "\"010203\"", "010203"),
    )

    assertEquals(VelrValueType.values().toList(), values.map { it.type() }, "kotlin VelrValue type coverage")
    assertEquals(true, values[1].asBoolean(), "kotlin VelrValue bool")
    assertEquals(42L, values[2].asLong(), "kotlin VelrValue int64")
    assertEquals(3.5, values[3].asDouble(), "kotlin VelrValue double")
    assertEquals("Ada", values[4].asString(), "kotlin VelrValue string")
    assertEquals("2024-05-01", values[5].asDateText(), "kotlin VelrValue date")
    assertEquals("10:35:00", values[6].asLocalTimeText(), "kotlin VelrValue local time")
    assertEquals("10:35:00+01:00", values[7].asZonedTimeText(), "kotlin VelrValue zoned time")
    assertEquals("2024-05-01T10:35:00", values[8].asLocalDateTimeText(), "kotlin VelrValue local datetime")
    assertEquals("2024-05-01T10:35:00+01:00", values[9].asZonedDateTimeText(), "kotlin VelrValue zoned datetime")
    assertEquals("P1DT2H", values[10].asDurationText(), "kotlin VelrValue duration")
    assertEquals(true, values[11].asPointGeoJson().contains("\"Point\""), "kotlin VelrValue point")
    assertEquals(true, values[12].asGeometryGeoJson().contains("\"LineString\""), "kotlin VelrValue geometry")
    assertEquals(true, values[13].asGeographyGeoJson().contains("\"Polygon\""), "kotlin VelrValue geography")
    assertEquals("[1,2]", values[14].asListJson(), "kotlin VelrValue list")
    assertEquals("[0.1,0.2]", values[15].asVectorJson(), "kotlin VelrValue vector")
    assertEquals(true, values[16].asBytes().contentEquals(byteArrayOf(1, 2, 3)), "kotlin VelrValue bytes")
}

private fun kotlinRoundTrip(db: Velr) {
    db.run("CREATE (:KotlinSmoke {name:'Ada', score:42})")
    val rows = db.query(
        "MATCH (n:KotlinSmoke) RETURN n.name AS name, n.score AS score",
        QueryOptions.maxResultRows(5),
    )
    assertEquals(1, rows.size, "kotlin query row count")
    assertEquals("Ada", rows[0]["name"], "kotlin query name")
    assertEquals(42L, rows[0]["score"], "kotlin query score")

    db.execOne("MATCH (n:KotlinSmoke) RETURN n.name AS name, n.score AS score").use { table ->
        val objectRows = table.toObjectRows()
        assertEquals(listOf("name", "score"), table.columnNames(), "kotlin column names")
        assertEquals("Ada", objectRows.single()["name"], "kotlin toObjectRows name")
        assertEquals(42L, objectRows.single()["score"], "kotlin toObjectRows score")
    }
}

private fun kotlinTransactionHelper(db: Velr) {
    val result = db.transaction { tx ->
        tx.run("CREATE (:KotlinTx {k:'committed'})")
        "ok"
    }
    assertEquals("ok", result, "kotlin transaction result")
    assertEquals(
        listOf("committed"),
        db.query("MATCH (n:KotlinTx) RETURN n.k AS k").map { it["k"] },
        "kotlin transaction commit",
    )

    var failed = false
    try {
        db.transaction { tx ->
            tx.run("CREATE (:KotlinTx {k:'rolled-back'})")
            throw IllegalStateException("boom")
        }
    } catch (e: IllegalStateException) {
        failed = true
    }
    assertEquals(true, failed, "kotlin transaction propagates error")
    assertEquals(
        listOf("committed"),
        db.query("MATCH (n:KotlinTx) RETURN n.k AS k ORDER BY k").map { it["k"] },
        "kotlin transaction rollback",
    )
}

private fun kotlinSavepointHelper(db: Velr) {
    db.transaction { tx ->
        tx.run("CREATE (:KotlinSp {k:'outer'})")
        tx.savepoint { sp ->
            tx.run("CREATE (:KotlinSp {k:'inner'})")
            sp.rollback()
        }
        tx.savepoint {
            tx.run("CREATE (:KotlinSp {k:'kept'})")
        }
    }
    assertEquals(
        listOf("kept", "outer"),
        db.query("MATCH (n:KotlinSp) RETURN n.k AS k ORDER BY k").map { it["k"] },
        "kotlin scoped savepoint helper",
    )
}

private fun kotlinRowsAndCells(db: Velr) {
    db.execOne(
        "RETURN null AS n, true AS b, 7 AS i, 1.5 AS f, 'text' AS t, ['x','y'] AS arr",
    ).use { table ->
        val row = table.collect().single()
        assertEquals(null, row[0].asAny(), "kotlin null cell")
        assertEquals(true, row[1].asAny(), "kotlin bool cell")
        assertEquals(7L, row[2].asAny(), "kotlin int cell")
        assertEquals(1.5, row[3].asAny(), "kotlin double cell")
        assertEquals("text", row[4].asAny(), "kotlin text cell")
        assertEquals("[\"x\",\"y\"]", row[5].asAny(), "kotlin json cell")
    }
}

private fun kotlinQueryOptions(db: Velr) {
    val rows = db.query(
        "UNWIND [1,2,3,4] AS x WITH x WHERE x >= ${'$'}min RETURN x ORDER BY x",
        QueryOptions.builder()
            .param("min", 2L)
            .maxResultRows(2)
            .build(),
    )
    assertEquals(listOf(2L, 3L), rows.map { it["x"] }, "kotlin params and max rows")
}

private fun kotlinVectorSmoke() {
    val dir = Files.createTempDirectory("velr-kotlin-vector-")
    val seen = mutableListOf<VectorEmbeddingInput>()
    try {
        velr(dir.resolve("vector.db").toString()).use { db ->
            db.run(
                    """
                    CREATE
                      (:KotlinVectorPaper {title:'Alpha Graphs', abstract:'alpha retrieval with embeddings', pages:12, active:true, published:date('2024-05-01'), tags:['graph','alpha']}),
                      (:KotlinVectorPaper {title:'Beta Notes', abstract:'planner internals', pages:5, active:false, published:date('2024-05-02'), tags:['planner']})
                    """.trimIndent(),
                )

            db.registerVectorEmbedder("toy") { inputs ->
                seen += inputs
                Array(inputs.size) { i ->
                    val input = inputs[i]
                    assertEquals(3, input.dimensions(), "kotlin vector dimensions")
                    when (input.purpose()) {
                        VectorEmbeddingPurpose.INDEX_ENTITY -> assertKotlinVectorIndexInput(input)
                        VectorEmbeddingPurpose.QUERY -> assertKotlinVectorQueryInput(input)
                    }
                    kotlinToyVector(input.text(), input.dimensions())
                }
            }

            db.run(
                    """
                    CREATE VECTOR INDEX kotlinPaperEmbedding IF NOT EXISTS
                    FOR (n:KotlinVectorPaper)
                    ON EACH [n.title, n.abstract, n.pages, n.active, n.published, n.tags]
                    OPTIONS { indexConfig: { dimensions: 3, metric: 'cosine', embedder: 'toy' } }
                    """.trimIndent(),
                )

            db.execOne(
                """
                CALL db.index.vector.queryNodes('kotlinPaperEmbedding', 1, 'alpha query')
                YIELD node, score
                RETURN node, score
                """.trimIndent(),
            ).use { table ->
                val row = table.collect().single()
                assertEquals(true, row[0].asString().contains("Alpha Graphs"), "kotlin vector node")
                assertEquals(CellType.DOUBLE, row[1].type(), "kotlin vector score type")
                assertEquals(true, row[1].asDouble() > 0.0, "kotlin vector score")
            }

            assertEquals(
                true,
                seen.any { it.purpose() == VectorEmbeddingPurpose.INDEX_ENTITY },
                "kotlin saw vector index input",
            )
            assertEquals(
                true,
                seen.any { it.purpose() == VectorEmbeddingPurpose.QUERY },
                "kotlin saw vector query input",
            )
        }
    } catch (e: VelrException) {
        if (!e.message.orEmpty().contains("vector-usearch")) {
            throw e
        }
    } finally {
        deleteTree(dir)
    }
}

private fun assertKotlinVectorIndexInput(input: VectorEmbeddingInput) {
    assertEquals("kotlinPaperEmbedding", input.indexName(), "kotlin vector index name")
    assertEquals(VectorEntityKind.NODE, input.entityKind(), "kotlin vector entity kind")
    assertEquals(true, input.entityId() != null, "kotlin vector entity id")

    val fields = input.fields().associateBy { it.name() }
    assertEquals(
        listOf("title", "abstract", "pages", "active", "published", "tags"),
        fields.keys.toList(),
        "kotlin vector field order",
    )
    val title = fields.getValue("title")
    assertEquals(VectorValueType.STRING, title.valueType(), "kotlin vector title type")
    assertEquals(VelrValueType.STRING, title.velrValue().type(), "kotlin VelrValue title type")
    assertEquals(true, title.velrValue().asString() in setOf("Alpha Graphs", "Beta Notes"), "kotlin vector title value")
    assertEquals(true, title.display().isNotEmpty(), "kotlin vector title display")

    val pages = fields.getValue("pages")
    assertEquals(VectorValueType.INT64, pages.valueType(), "kotlin vector pages type")
    assertEquals(VelrValueType.INT64, pages.velrValue().type(), "kotlin VelrValue pages type")
    assertEquals(true, pages.velrValue().asLong() > 0L, "kotlin vector pages value")

    val active = fields.getValue("active")
    assertEquals(VectorValueType.BOOL, active.valueType(), "kotlin vector active type")
    assertEquals(VelrValueType.BOOL, active.velrValue().type(), "kotlin VelrValue active type")
    active.velrValue().asBoolean()

    val published = fields.getValue("published")
    assertEquals(VectorValueType.DATE, published.valueType(), "kotlin vector published type")
    assertEquals(VelrValueType.DATE, published.velrValue().type(), "kotlin VelrValue published type")
    assertEquals(
        true,
        published.velrValue().asDateText() in setOf("2024-05-01", "2024-05-02"),
        "kotlin vector published date",
    )

    val tags = fields.getValue("tags")
    assertEquals(VectorValueType.LIST, tags.valueType(), "kotlin vector tags type")
    assertEquals(VelrValueType.LIST, tags.velrValue().type(), "kotlin VelrValue tags type")
    assertEquals(true, tags.velrValue().asListJson().startsWith("["), "kotlin vector tags json")
    assertEquals(true, tags.display().isNotEmpty(), "kotlin vector tags display")
}

private fun assertKotlinVectorQueryInput(input: VectorEmbeddingInput) {
    assertEquals("kotlinPaperEmbedding", input.indexName(), "kotlin vector query index name")
    assertEquals(null, input.entityKind(), "kotlin vector query entity kind")
    assertEquals(null, input.entityId(), "kotlin vector query entity id")
    assertEquals(1, input.fields().size, "kotlin vector query field count")
    val field = input.fields().single()
    assertEquals(null, field.name(), "kotlin vector query field name")
    assertEquals(VectorValueType.STRING, field.valueType(), "kotlin vector query field type")
    assertEquals(VelrValueType.STRING, field.velrValue().type(), "kotlin VelrValue query field type")
    assertEquals("alpha query", field.velrValue().asString(), "kotlin VelrValue query text value")
    assertEquals("alpha query", field.value(), "kotlin vector query text value")
    assertEquals("alpha query", input.text(), "kotlin vector query text")
}

private fun kotlinToyVector(text: String, dimensions: Int): FloatArray {
    val lower = text.lowercase()
    return FloatArray(dimensions).also { out ->
        if (dimensions > 0) out[0] = if ("alpha" in lower) 1.0f else 0.0f
        if (dimensions > 1) out[1] = if ("beta" in lower || "planner" in lower) 1.0f else 0.0f
        if (dimensions > 2) out[2] = if ("graph" in lower || "query" in lower) 0.5f else 0.1f
    }
}

private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

private fun assertEquals(expected: Any?, actual: Any?, what: String) {
    if (expected != actual) {
        throw AssertionError("$what: expected $expected, got $actual")
    }
}
