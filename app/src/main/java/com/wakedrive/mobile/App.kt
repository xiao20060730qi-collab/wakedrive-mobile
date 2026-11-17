package com.wakedrive.mobile

import android.app.Application
import com.wakedrive.mobile.net.ApiClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}
