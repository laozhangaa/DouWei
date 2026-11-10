package com.laozhang.mockgps.model

import org.junit.Assert.*
import org.junit.Test

class ProvinceCityDataTest {
    private fun provinces() = javaClass.getResourceAsStream("/province_cities.json")!!
        .bufferedReader().use(ProvinceCityData::read)

    @Test fun guizhouContainsGuiyangAndZunyi() {
        val cities = provinces().first { it.name == "贵州省" }.cities
        assertTrue(cities.any { it.name == "贵阳市" && it.code == "520100" })
        assertTrue(cities.any { it.name == "遵义市" && it.code == "520300" })
    }

    @Test fun municipalitiesUseSearchableCityNames() {
        for (name in listOf("北京市", "天津市", "上海市", "重庆市")) {
            assertEquals(listOf(name), provinces().first { it.name == name }.cities.map { it.name })
        }
    }

    @Test fun directlyAdministeredCitiesAreSelectable() {
        val data = provinces()
        assertTrue(data.first { it.name == "海南省" }.cities.any { it.name == "五指山市" })
        assertTrue(data.first { it.name == "新疆维吾尔自治区" }.cities.any { it.name == "石河子市" })
        assertTrue(data.flatMap { it.cities }.none { "直辖县级行政区划" in it.name })
    }

    @Test fun allEntriesHaveUniqueSixDigitSearchCodes() {
        val data = provinces()
        assertEquals(31, data.size)
        val cities = data.flatMap { it.cities }
        assertTrue(data.all { it.cities.isNotEmpty() })
        assertTrue(cities.all { it.name.isNotBlank() && it.code.matches(Regex("[0-9]{6}")) })
        assertEquals(cities.size, cities.map { it.code }.distinct().size)
    }
}
