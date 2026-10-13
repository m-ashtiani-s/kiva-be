package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads the province and city lists that V11__geo.sql fills into the freshly migrated kiva_test. */
class GeoRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = GeoRepositoryImpl()

    @Test
    fun `the 31 provinces come back in list order, which is Persian alphabetical`() {
        val provinces = transaction(db) { repository.findAllProvinces() }

        assertEquals(31, provinces.size)
        assertEquals((1..31).toList(), provinces.map { it.sortOrder })
        assertEquals(listOf("آذربایجان شرقی", "آذربایجان غربی", "اردبیل"), provinces.take(3).map { it.name })
        // Persian, not Unicode, order: «چ» comes right after «ت», not after «ی».
        assertEquals(listOf("تهران", "چهارمحال و بختیاری"), provinces.subList(7, 9).map { it.name })
        assertEquals("یزد", provinces.last().name)
    }

    @Test
    fun `all 1319 cities come back province by province, each province's in list order`() {
        val cities = transaction(db) { repository.findAllCities() }

        assertEquals(1319, cities.size)
        assertEquals(cities.sortedWith(compareBy({ it.provinceId.value }, { it.sortOrder })), cities)
    }

    @Test
    fun `findProvince finds Tehran, and null for an id nobody uses`() {
        val (tehran, nowhere) = transaction(db) {
            repository.findProvince(ProvinceId.constructFromPersisted(8)) to repository.findProvince(ProvinceId.constructFromPersisted(99))
        }

        assertEquals("تهران", tehran?.name)
        assertEquals(8, tehran?.sortOrder)
        assertNull(nowhere)
    }

    @Test
    fun `findCitiesOf gives one province's cities in list order, with every column mapped`() {
        val cities = transaction(db) { repository.findCitiesOf(ProvinceId.constructFromPersisted(8)) }

        assertTrue(cities.all { it.provinceId.value == 8 })
        assertEquals((1..cities.size).toList(), cities.map { it.sortOrder })
        val tehran = cities.single { it.name == "تهران" }
        assertEquals(394, tehran.id.value)
        assertEquals("آبسرد", cities.first().name)
    }

    @Test
    fun `findCitiesOf is empty for a province that doesn't exist`() {
        assertEquals(emptyList(), transaction(db) { repository.findCitiesOf(ProvinceId.constructFromPersisted(99)) })
    }
}
