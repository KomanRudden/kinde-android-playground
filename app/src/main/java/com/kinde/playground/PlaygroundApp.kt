package com.kinde.playground

import android.app.Application
import com.kinde.playground.core.KindeHub

class PlaygroundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Programmatic configuration must happen here, before any activity builds a
        // KindeSDK facade: the client's configuration is fixed for the process.
        KindeHub.initialize(this)
    }
}
