package com.teddybear.aura.player

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

private const val TAG = "AuraBluetooth"

/**
 * Manages per-Bluetooth-device EQ/volume profiles.
 *
 * When a BT device connects for the first time, emits [pendingDeviceName]
 * so the UI can ask the user "What type is this?" (Headphones / Speaker / Car / Other).
 * After classification, saves current EQ state as that device's profile.
 * On subsequent connections, auto-applies the saved profile.
 *
 * Profiles stored as JSON in filesDir/bt_profiles/.
 */
class BluetoothProfileManager(
    private val context: Context,
    private val getEqState: () -> EqState,
    private val applyEqState: (EqState) -> Unit,
) {
    enum class DeviceType { HEADPHONES, SPEAKER, CAR, OTHER }

    data class DeviceProfile(
        val deviceAddress: String,
        val deviceName:    String,
        val type:          DeviceType,
        val eqBands:       List<Int>,   // 5 band values in milliBel
        val bassBoost:     Int,
        val virtualizer:   Int,
    )

    private val profileDir = File(context.filesDir, "bt_profiles").also { it.mkdirs() }

    // Emits device name when a new unknown device connects — UI should show dialog
    private val _pendingDevice = MutableStateFlow<BluetoothDevice?>(null)
    val pendingDevice: StateFlow<BluetoothDevice?> = _pendingDevice

    // Whether collection is enabled (user can disable in Settings)
    var collectionEnabled: Boolean
        get()  = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
                     .getBoolean("bt_collection_enabled", true)
        set(v) = context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
                     .edit().putBoolean("bt_collection_enabled", v).apply()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!collectionEnabled) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            device ?: return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onDeviceConnected(device)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Save current EQ state to profile (learn from manual adjustments)
                    saveCurrentStateForDevice(device)
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try { context.registerReceiver(receiver, filter) }
        catch (e: Exception) { Log.w(TAG, "Register failed: ${e.message}") }
        syncConnectedDevices()
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) }
        catch (_: Exception) {}
    }

    private fun syncConnectedDevices() {
        if (!collectionEnabled || !hasBluetoothConnectPermission()) return
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val seenAddresses = HashSet<String>()
        try {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
                bluetoothManager.getConnectedDevices(profile).forEach { device ->
                    if (seenAddresses.add(device.address)) {
                        onDeviceConnected(device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial BT sync failed: ${e.message}")
        }

        val adapter = bluetoothManager.adapter ?: return
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            try {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                            try {
                                proxy.connectedDevices.forEach { device ->
                                    if (seenAddresses.add(device.address)) {
                                        onDeviceConnected(device)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Proxy sync failed: ${e.message}")
                            } finally {
                                adapter.closeProfileProxy(profileId, proxy)
                            }
                        }

                        override fun onServiceDisconnected(profileId: Int) = Unit
                    },
                    profile,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Profile proxy failed: ${e.message}")
            }
        }
    }

    private fun onDeviceConnected(device: BluetoothDevice) {
        val profile = loadProfile(device.address)
        if (profile != null) {
            // Known device — apply saved profile
            applyProfile(profile)
            Log.i(TAG, "Applied profile for ${device.name} (${profile.type})")
        } else {
            // Unknown device — ask user what type it is
            _pendingDevice.value = device
            Log.i(TAG, "New device: ${device.name} — waiting for user classification")
        }
    }

    /** Called after user picks DeviceType for a new device. */
    fun classifyDevice(device: BluetoothDevice, type: DeviceType) {
        val eq     = getEqState()
        val profile = DeviceProfile(
            deviceAddress = device.address,
            deviceName    = device.name ?: "Unknown",
            type          = type,
            eqBands       = eq.bands,
            bassBoost     = eq.bassBoost,
            virtualizer   = eq.virtualizer,
        )
        saveProfile(profile)
        _pendingDevice.value = null
        Log.i(TAG, "Classified ${profile.deviceName} as ${type.name}, saved EQ profile")
    }

    fun dismissPendingDevice() { _pendingDevice.value = null }

    private fun saveCurrentStateForDevice(device: BluetoothDevice) {
        val existing = loadProfile(device.address) ?: return  // only update if known
        val eq       = getEqState()
        saveProfile(existing.copy(eqBands = eq.bands, bassBoost = eq.bassBoost, virtualizer = eq.virtualizer))
    }

    private fun applyProfile(profile: DeviceProfile) {
        val current = getEqState()
        applyEqState(
            current.copy(
                bands = profile.eqBands,
                bassBoost = profile.bassBoost,
                virtualizer = profile.virtualizer,
                presetIndex = 0,
            )
        )
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun profileFile(address: String) =
        File(profileDir, address.replace(":", "_") + ".json")

    private fun saveProfile(p: DeviceProfile) {
        try {
            profileFile(p.deviceAddress).writeText(JSONObject().apply {
                put("address",   p.deviceAddress)
                put("name",      p.deviceName)
                put("type",      p.type.name)
                put("bands",     p.eqBands.joinToString(","))
                put("bass",      p.bassBoost)
                put("virt",      p.virtualizer)
            }.toString())
        } catch (e: Exception) { Log.e(TAG, "Save profile: ${e.message}") }
    }

    private fun loadProfile(address: String): DeviceProfile? {
        return try {
            val f = profileFile(address)
            if (!f.exists()) return null
            val j = JSONObject(f.readText())
            DeviceProfile(
                deviceAddress = j.getString("address"),
                deviceName    = j.getString("name"),
                type          = DeviceType.valueOf(j.getString("type")),
                eqBands       = j.getString("bands").split(",").map { it.trim().toInt() },
                bassBoost     = j.getInt("bass"),
                virtualizer   = j.getInt("virt"),
            )
        } catch (e: Exception) { Log.w(TAG, "Load profile: ${e.message}"); null }
    }

    fun getAllProfiles(): List<DeviceProfile> =
        profileDir.listFiles()?.mapNotNull { loadProfile(it.nameWithoutExtension.replace("_", ":")) } ?: emptyList()

    fun deleteProfile(address: String) { profileFile(address).delete() }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
