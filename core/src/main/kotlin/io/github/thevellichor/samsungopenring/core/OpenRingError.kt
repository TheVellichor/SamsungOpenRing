package io.github.thevellichor.samsungopenring.core

sealed class OpenRingError(val message: String) {
    data object RingNotFound : OpenRingError("Galaxy Ring not found in bonded devices")
    data object BluetoothDisabled : OpenRingError("Bluetooth is disabled")
    data object PermissionDenied : OpenRingError("Bluetooth permission not granted")
    data object ServiceDiscoveryFailed : OpenRingError("BLE service discovery failed")
    data object CharacteristicNotFound : OpenRingError("Ring TX characteristic not found")
    data object NotConnected : OpenRingError("Not connected to ring")
}
