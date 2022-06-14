package gorda.driver.interfaces

import android.location.Location

interface CustomLocationListener {
    fun onLocationChanged(location: Location?): Unit
}