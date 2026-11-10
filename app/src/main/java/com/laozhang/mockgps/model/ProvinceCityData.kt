package com.laozhang.mockgps.model

import com.google.gson.Gson
import java.io.Reader

object ProvinceCityData {
    data class City(val code: String, val name: String)
    data class Province(val code: String, val name: String, val cities: List<City>)

    fun read(reader: Reader): List<Province> =
        Gson().fromJson(reader, Array<Province>::class.java).toList()
}
