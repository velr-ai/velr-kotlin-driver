package ai.velr

/**
 * Open a Velr connection.
 *
 * Pass null for an in-memory database. Close the returned connection with `use` when possible.
 */
fun velr(path: String? = null): Velr = Velr.open(path)

/**
 * Create one Arrow C Data Interface chunk from `ArrowSchema` and `ArrowArray` struct addresses.
 *
 * The `ArrowArray` is transferred to Velr when the chunk is passed to `bindArrow` or
 * `bindArrowChunks`; the `ArrowSchema` is not consumed.
 */
fun arrowChunk(schemaAddress: Long, arrayAddress: Long): ArrowColumn.Chunk =
    ArrowColumn.chunk(schemaAddress, arrayAddress)

/**
 * Create a single-chunk Arrow C Data Interface column from struct addresses.
 *
 * The `ArrowArray` is transferred to Velr when the column is bound; the `ArrowSchema` is not
 * consumed.
 */
fun arrowColumn(name: String, schemaAddress: Long, arrayAddress: Long): ArrowColumn =
    ArrowColumn.cData(name, schemaAddress, arrayAddress)

/**
 * Create a chunked Arrow C Data Interface column.
 *
 * Each chunk's `ArrowArray` is transferred to Velr when the column is bound; schemas are not
 * consumed.
 */
fun arrowColumn(name: String, vararg chunks: ArrowColumn.Chunk): ArrowColumn =
    ArrowColumn.chunks(name, *chunks)

/** Run a block inside a transaction, committing on success and rolling back on failure. */
inline fun <T> Velr.transaction(block: (VelrTx) -> T): T {
    val tx = beginTx()
    var committed = false
    try {
        val result = block(tx)
        if (!tx.isClosed) tx.commit()
        committed = true
        return result
    } finally {
        if (!committed && !tx.isClosed) tx.rollback()
    }
}

/** Run a block inside a scoped savepoint, releasing on success and rolling back on failure. */
inline fun <T> VelrTx.savepoint(block: (Savepoint) -> T): T {
    val sp = savepoint()
    var released = false
    try {
        val result = block(sp)
        if (!sp.isClosed) sp.release()
        released = true
        return result
    } finally {
        if (!released && !sp.isClosed) sp.rollback()
    }
}

/** Return this result cell as its idiomatic Kotlin nullable value. */
fun Cell.asAny(): Any? = asObject()

/** Collect this table as rows keyed by column name with Kotlin nullable values. */
fun Table.toObjectRows(): List<Map<String, Any?>> =
    toMaps().map { row -> row.mapValues { it.value } }
