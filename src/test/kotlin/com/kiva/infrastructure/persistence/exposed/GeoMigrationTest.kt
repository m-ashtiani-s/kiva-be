package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.CityTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProvinceTbl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V11__geo.sql: the data it fills (with the fixes of PURCHASE_ROADMAP.md, section 5.1) and the rules the database
 * keeps on it, whatever writes there. Every write here is rolled back, so the list stays as it is for the other tests.
 */
class GeoMigrationTest {

    private val db = TestDatabase.database

    private fun assertRejected(write: JdbcTransaction.() -> Unit) = transaction(db) {
        assertFailsWith<ExposedSQLException> { write() }
        rollback()
    }

    private fun insertCity(cityId: Int = 2000, provinceId: Int = 8, name: String = "شهر آزمایشی") {
        CityTbl.insert {
            it[CityTbl.cityId] = cityId
            it[CityTbl.provinceId] = provinceId
            it[CityTbl.name] = name
            it[sortOrder] = 99
        }
    }

    private fun cityName(cityId: Int): String? = transaction(db) {
        CityTbl.selectAll().where { CityTbl.cityId eq cityId }.singleOrNull()?.get(CityTbl.name)
    }

    @Test
    fun `31 provinces and 1319 cities`() {
        val (provinces, cities) = transaction(db) { ProvinceTbl.selectAll().count() to CityTbl.selectAll().count() }

        assertEquals(31, provinces)
        assertEquals(1319, cities)
    }

    @Test
    fun `the three provinces with a glued «و» are written with spaces`() {
        val names = transaction(db) {
            ProvinceTbl.selectAll().where { ProvinceTbl.provinceId inList listOf(9, 16, 23) }.map { it[ProvinceTbl.name] }.toSet()
        }

        assertEquals(setOf("چهارمحال و بختیاری", "سیستان و بلوچستان", "کهگیلویه و بویراحمد"), names)
    }

    @Test
    fun `the cities with a glued «و» are fixed, and «چاه ورز» is a real name that stays`() {
        assertEquals("آران و بیدگل", cityName(23))
        assertEquals("بویین و میاندشت", cityName(300))
        assertEquals("کتالم و سادات شهر", cityName(1090))
        assertEquals("چاه ورز", cityName(465))
    }

    @Test
    fun `Tabriz's postal districts and the second «گلوگاه» are left out`() {
        val tabrizDistricts = transaction(db) { CityTbl.selectAll().where { CityTbl.name like "تبریز%-" }.count() }

        assertEquals(0, tabrizDistricts)
        assertEquals("تبریز", cityName(361))
        assertEquals("گلوگاه", cityName(1216))
        assertEquals(null, cityName(1217))
    }

    @Test
    fun `«اباد» as a word of its own is written «آباد», joined names and names in brackets stay`() {
        val separateAbad = transaction(db) { CityTbl.selectAll().where { CityTbl.name like "% اباد%" }.count() }

        assertEquals(0, separateAbad)
        assertEquals("علی آباد", cityName(966))
        assertEquals("اسلام آباد", cityName(108))
        assertEquals("کارزین (فتح آباد)", cityName(1077))
        assertEquals("خداجو(خراجو)", cityName(526))
    }

    @Test
    fun `the city of Tehran is 394 in the province of Tehran (8), not the contract's old 301`() {
        val tehran = transaction(db) {
            CityTbl.selectAll().where { (CityTbl.provinceId eq 8) and (CityTbl.name eq "تهران") }.single()[CityTbl.cityId]
        }

        assertEquals(394, tehran)
        assertTrue(cityName(301) != "تهران")
    }

    @Test
    fun `a new city is accepted (and rolled back)`() {
        transaction(db) {
            insertCity()
            rollback()
        }
    }

    @Test
    fun `a city of a province that doesn't exist is refused`() = assertRejected { insertCity(provinceId = 99) }

    @Test
    fun `two cities with the same name in one province are refused`() = assertRejected { insertCity(name = "تهران") }

    @Test
    fun `the same name in another province is fine, as «حسن آباد» of Fars and of Isfahan`() {
        val provinces = transaction(db) { CityTbl.selectAll().where { CityTbl.name eq "حسن آباد" }.map { it[CityTbl.provinceId] }.toSet() }

        assertEquals(setOf(4, 8, 17), provinces)
    }

    @Test
    fun `a city id can be used only once`() = assertRejected { insertCity(cityId = 394) }

    @Test
    fun `a blank city name is refused`() = assertRejected { insertCity(name = "  ") }

    @Test
    fun `a province name can be used only once`() = assertRejected {
        ProvinceTbl.insert {
            it[provinceId] = 32
            it[name] = "تهران"
            it[sortOrder] = 32
        }
    }
}
