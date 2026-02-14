package com.siddharth.factoryattendance.kiosk

import java.security.MessageDigest

object AdminSecurity {

    // PIN = 1234 (change later)
    private const val PIN_HASH = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

    private const val MAX_ATTEMPTS = 3
    private const val LOCKOUT_MS = 5 * 60 * 1000L

    private var failedAttempts = 0
    private var lockedUntil: Long = 0

    fun canAttempt(): Boolean {
        return System.currentTimeMillis() > lockedUntil
    }

    fun registerFailure() {
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
            failedAttempts = 0
        }
    }

    fun resetFailures() {
        failedAttempts = 0
    }

    fun isValidPin(pin: String): Boolean {
        return sha256(pin) == PIN_HASH
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}