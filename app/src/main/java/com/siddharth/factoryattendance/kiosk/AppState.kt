package com.siddharth.factoryattendance.kiosk

object AppState {
    @Volatile
    var isAdminModeActive: Boolean = false
    @Volatile var isCameraActive = false
}