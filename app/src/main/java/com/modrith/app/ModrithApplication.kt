package com.modrith.app

import android.app.Application
import com.modrith.utils.logging.ReleaseTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class ModrithApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }
}
