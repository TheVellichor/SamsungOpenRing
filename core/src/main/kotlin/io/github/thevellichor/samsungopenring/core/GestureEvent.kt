package io.github.thevellichor.samsungopenring.core

data class GestureEvent(
    val gestureId: Int,
    val timestamp: Long = System.currentTimeMillis(),
)
