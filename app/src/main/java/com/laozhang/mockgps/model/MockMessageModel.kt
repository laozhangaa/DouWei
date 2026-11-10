package com.laozhang.mockgps.model

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.IntDef
import com.laozhang.mockgps.utils.LocationUtils
import com.google.gson.Gson


@IntDef(NaviType.NONE, NaviType.LOCATION, NaviType.NAVI, NaviType.NAVI_FILE)
@Retention(AnnotationRetention.SOURCE)
annotation class NaviType {
    companion object {
        const val NONE = -1
        const val LOCATION = 0
        const val NAVI = 1
        const val NAVI_FILE = 2
    }
}

/**
 * @author jiayu.liu
 */
data class MockMessageModel(
    var locationModel: PoiInfoModel? = null,
    var startNavi: PoiInfoModel? = null,
    var wayNaviList: List<PoiInfoModel>? = null,
    var endNavi: PoiInfoModel? = null,
    /**
     * 0.模拟定位  1.模拟导航  2.文件数据导航
     */
    @NaviType
    var naviType: Int = 0,
    /***
     * 速度 单位 KM/H
     */
    var speed: Int = 60,
    var uid: String? = "",
    var pointType: String? = LocationUtils.gcj02,
    var travelMode: String? = TravelMode.DRIVING.name,
    var navigationOptions: NavigationOptions? = null,
    var recoveryId: String? = null
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readParcelable(PoiInfoModel::class.java.classLoader),
        parcel.readParcelable(PoiInfoModel::class.java.classLoader),
        parcel.createTypedArrayList(PoiInfoModel),
        parcel.readParcelable(PoiInfoModel::class.java.classLoader),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readString(),
        parcel.readString(),
        parcel.readString(),
        parcel.readString()?.let { Gson().fromJson(it, NavigationOptions::class.java) },
        parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(locationModel, flags)
        parcel.writeParcelable(startNavi, flags)
        parcel.writeTypedList(wayNaviList)
        parcel.writeParcelable(endNavi, flags)
        parcel.writeInt(naviType)
        parcel.writeInt(speed)
        parcel.writeString(uid)
        parcel.writeString(pointType)
        parcel.writeString(travelMode)
        parcel.writeString(navigationOptions?.let { Gson().toJson(it) })
        parcel.writeString(recoveryId)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<MockMessageModel> {
        override fun createFromParcel(parcel: Parcel): MockMessageModel {
            return MockMessageModel(parcel)
        }

        override fun newArray(size: Int): Array<MockMessageModel?> {
            return arrayOfNulls(size)
        }
    }

}
