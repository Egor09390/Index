package io.nekohasekai.sagernet.bg.test

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import io.nekohasekai.sagernet.SagerNet

internal object QualityEnvironment {
    @Suppress("DEPRECATION")
    fun describe(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = SagerNet.underlyingNetwork ?: cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                when (cm?.getNetworkInfo(network)?.subtype) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    else -> "Мобильная сеть"
                }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Сеть не определена"
        }
        val level = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return listOfNotNull(transport, level?.takeIf { it in 0..20 }?.let { "заряд $it%" },
            "Энергосбережение".takeIf { saver }).joinToString(" · ")
    }
}
