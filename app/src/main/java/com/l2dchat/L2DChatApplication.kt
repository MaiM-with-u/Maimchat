package com.l2dchat

import android.app.Application
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule

class L2DChatApplication : Application() {
    private val logger by lazy { L2DLogger.module(LogModule.MAIN_VIEW) }

    override fun onCreate() {
        super.onCreate()
        L2DLogger.init(this)
        logger.info("L2DChatApplication initialized")
    }
}
