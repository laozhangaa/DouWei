package com.laozhang.mockgps

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.blankj.utilcode.util.Utils
import com.castiel.common.BaseApp
import me.weishu.reflection.Reflection
import java.lang.reflect.Field


/**
 * @author jiayu.liu
 */
class MyApp : BaseApp() {
    private lateinit var mMockReceiver: MockReceiver
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Reflection.unseal(base)
        reflectionValueAnimator()
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun reflectionValueAnimator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    val field: Field = ValueAnimator::class.java.getDeclaredField("sDurationScale")
                    field.isAccessible = true
                    field.set(null, 1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    override fun onCreate() {
        super.onCreate()

        Utils.init(this)
        // Map SDKs are initialized lazily, only after the user supplies a key.
        initReceiver()
    }


    private fun initReceiver() {
        mMockReceiver = MockReceiver()
        val intentFilter = IntentFilter()
        intentFilter.addAction("com.laozhang.mockgps.navi")
        registerReceiver(mMockReceiver, intentFilter)
//        mMockReceiver is missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag for unprotected broadcasts registered for com.laozhang.mockgps.navi
    }
}
