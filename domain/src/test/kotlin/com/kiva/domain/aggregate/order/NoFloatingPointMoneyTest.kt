package com.kiva.domain.aggregate.order

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * A guard on the code itself: nothing that works out an order's money may use Double or Float, which can't hold most
 * decimal amounts exactly. Comments are left out, so explaining why Double is wrong is still allowed.
 */
class NoFloatingPointMoneyTest {

    // Gradle runs a module's tests from the module's own directory.
    private val moneyPackages = listOf(
        "src/main/kotlin/com/kiva/domain/aggregate/order",
        "src/main/kotlin/com/kiva/domain/aggregate/settings",
    )

    private val floatingPoint = Regex("""\b(Double|Float)\b|\.to(Double|Float)\(""")
    private val comments = Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL)

    @Test
    fun `no Double or Float in the order and settings code`() {
        val files = moneyPackages.flatMap { File(it).walk().filter { file -> file.extension == "kt" }.toList() }
        assertTrue(files.isNotEmpty(), "no source found under $moneyPackages; is the working directory the domain module?")

        val offenders = files.filter { floatingPoint.containsMatchIn(it.readText().replace(comments, "")) }

        assertTrue(offenders.isEmpty(), "Double or Float in: ${offenders.map { it.name }}")
    }
}
