package com.amurayada.guardianapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Gestiona la conexión BLE con la GuardianBand y procesa los datos de frecuencia cardíaca.
 */
class BLEManager private constructor(private val context: Context) {
    private val TAG = "BLEManager"
    
    // UUIDs estándar para el servicio de frecuencia cardíaca (Heart Rate)
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    
    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState
    
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Conectado al servidor GATT de GuardianBand.")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Desconectado del servidor GATT.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(HEART_RATE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
                
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.i(TAG, "Notificaciones de pulso activadas.")
                    }
                } else {
                    Log.w(TAG, "Característica de pulso no encontrada en el servicio.")
                }
            } else {
                Log.w(TAG, "Descubrimiento de servicios falló con status: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processHeartRateData(characteristic)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processHeartRateData(characteristic, value)
        }
    }

    private fun processHeartRateData(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
        if (characteristic.uuid == HEART_RATE_MEASUREMENT_CHAR_UUID) {
            val data = value ?: characteristic.value
            if (data != null && data.isNotEmpty()) {
                // El primer byte son las banderas
                val flag = data[0].toInt()
                val format = if (flag and 0x01 != 0) {
                    BluetoothGattCharacteristic.FORMAT_UINT16
                } else {
                    BluetoothGattCharacteristic.FORMAT_UINT8
                }
                
                // Extraer el valor del pulso (índice 1)
                val heartRateValue = if (format == BluetoothGattCharacteristic.FORMAT_UINT8) {
                    data[1].toInt() and 0xFF
                } else {
                    // Simplificado para el ejemplo
                    ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                }
                
                _heartRate.value = heartRateValue
                Log.d(TAG, "Pulso recibido: $heartRateValue bpm")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.i(TAG, "Iniciando conexión con ${device.name ?: "Dispositivo"} (${device.address})")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: BLEManager? = null

        fun getInstance(context: Context): BLEManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BLEManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
