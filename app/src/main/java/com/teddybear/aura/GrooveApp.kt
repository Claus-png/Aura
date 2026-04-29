package com.teddybear.aura

import android.app.Application
import androidx.work.Configuration

class GrooveApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
