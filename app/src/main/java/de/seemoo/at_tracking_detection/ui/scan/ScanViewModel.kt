package de.seemoo.at_tracking_detection.ui.scan

import android.bluetooth.le.ScanResult
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import dagger.hilt.android.lifecycle.HiltViewModel
import de.seemoo.at_tracking_detection.database.models.device.BaseDevice
import de.seemoo.at_tracking_detection.database.models.device.DeviceManager
import de.seemoo.at_tracking_detection.database.models.device.BaseDevice.Companion.getPublicKey
import de.seemoo.at_tracking_detection.database.models.device.DeviceManager.getDeviceType
import de.seemoo.at_tracking_detection.database.models.device.DeviceType.Companion.getAllowedDeviceTypesFromSettings
import de.seemoo.at_tracking_detection.database.repository.BeaconRepository
import de.seemoo.at_tracking_detection.database.repository.ScanRepository
import de.seemoo.at_tracking_detection.detection.LocationProvider
import de.seemoo.at_tracking_detection.detection.ScanBluetoothWorker
import de.seemoo.at_tracking_detection.detection.ScanBluetoothWorker.Companion.TIME_BETWEEN_BEACONS
import de.seemoo.at_tracking_detection.util.SharedPrefs
import de.seemoo.at_tracking_detection.util.Utility
import de.seemoo.at_tracking_detection.util.ble.BLEScanner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val beaconRepository: BeaconRepository,
    private val locationProvider: LocationProvider,
    ) : ViewModel() {

    val bluetoothDeviceList = MutableLiveData<MutableList<ScanResult>>()

    val scanFinished = MutableLiveData(false)

    val sortingOrder = MutableLiveData<SortingOrder>(SortingOrder.SIGNAL_STRENGTH)

    val scanStart = MutableLiveData(LocalDateTime.MIN)

    var bluetoothEnabled = MutableLiveData(true)
    init {
        bluetoothDeviceList.value = ArrayList()
        bluetoothEnabled.value = BLEScanner.isBluetoothOn()
    }

    private fun <T> MutableLiveData<T>.notifyObserver() {
        this.value = this.value
    }

    fun addScanResult(scanResult: ScanResult) {
        if (scanFinished.value == true) {
            return
        }

        val deviceType = getDeviceType(scanResult)
        val validDeviceTypes = getAllowedDeviceTypesFromSettings()

        if (deviceType !in validDeviceTypes) {
            // If device not selected in settings then do not add ScanResult to list or database
            return
        }

        val currentDate = LocalDateTime.now()
        val uniqueIdentifier = getPublicKey(scanResult) // either public key or MAC-Address
        if (beaconRepository.getNumberOfBeaconsAddress(
            deviceAddress = uniqueIdentifier,
            since = currentDate.minusMinutes(TIME_BETWEEN_BEACONS)
        ) == 0) {
            // There was no beacon with the address saved in the last IME_BETWEEN_BEACONS minutes

            val location = locationProvider.getLastLocation() // if not working: checkRequirements = false
            Timber.d("Got location $location in ScanViewModel")

            MainScope().async {
                ScanBluetoothWorker.insertScanResult(
                    scanResult = scanResult,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    accuracy = location?.accuracy,
                    discoveryDate = currentDate,
                )
            }
        }

        val bluetoothDeviceListValue = bluetoothDeviceList.value ?: return
        bluetoothDeviceListValue.removeIf {
            getPublicKey(it) == uniqueIdentifier
        }

        if (SharedPrefs.showConnectedDevices || BaseDevice.getConnectionState(scanResult) in DeviceManager.savedConnectionStates) {
            // only add possible devices to list
            bluetoothDeviceListValue.add(scanResult)
        }

        if (!SharedPrefs.showConnectedDevices){
            // Do not show connected devices when criteria is met
            bluetoothDeviceListValue.removeIf {
                BaseDevice.getConnectionState(it) !in DeviceManager.savedConnectionStates
            }
        }

        sortResults(bluetoothDeviceListValue)

        bluetoothDeviceList.postValue(bluetoothDeviceListValue)
        Timber.d("Adding scan result ${scanResult.device.address} with unique identifier $uniqueIdentifier")
        Timber.d(
            "status bytes: ${
                scanResult.scanRecord?.manufacturerSpecificData?.get(76)?.get(2)?.toString(2)
            }"
        )
        Timber.d("Device list: ${bluetoothDeviceList.value?.count()}")
    }

    fun sortResults(bluetoothDeviceListValue: MutableList<ScanResult>) {
        when(sortingOrder.value) {
            SortingOrder.SIGNAL_STRENGTH -> bluetoothDeviceListValue.sortByDescending { it.rssi }
            SortingOrder.DETECTION_ORDER -> bluetoothDeviceListValue.sortByDescending { it.timestampNanos }
            SortingOrder.ADDRESS -> bluetoothDeviceListValue.sortBy { it.device.address }
            else -> bluetoothDeviceListValue.sortByDescending { it.rssi }
        }
    }

    fun changeColorOf(sortOptions: List<TextView>, sortOption: TextView) {
        val theme = Utility.getSelectedTheme()
        var color = Color.Gray
        if (theme){
            color = Color.LightGray
        }

        sortOptions.forEach {
            if(it == sortOption) {
                it.setBackgroundColor(color.toArgb())
            } else {
                it.setBackgroundColor(Color.Transparent.toArgb())
            }
        }
    }

    val isListEmpty: LiveData<Boolean> = bluetoothDeviceList.map { it.isEmpty() }

    val listSize: LiveData<Int> = bluetoothDeviceList.map { it.size }
}