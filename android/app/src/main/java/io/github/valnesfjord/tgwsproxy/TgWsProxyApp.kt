package io.github.valnesfjord.tgwsproxy

import android.app.Application

class TgWsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeProxy.load()
        ProxyBridge.syncFromNative()
    }
}
