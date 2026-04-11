package co.crackn.kompressor.testutil

/** Loads a test resource from the JVM classpath (androidHostTest runs on host JVM). */
@Suppress("unused") // Infrastructure for fixture-based golden tests
fun readTestResource(path: String): ByteArray {
    val stream = Thread.currentThread().contextClassLoader!!.getResourceAsStream(path)
        ?: error("Test resource not found on classpath: $path")
    return stream.use { it.readBytes() }
}
