package gorda.driver.ui.service

import android.location.Location

sealed class LocationUpdates {
    data class LastLocation(var location: Location) : LocationUpdates()

    companion object {
        fun lastLocation(location: Location): LocationUpdates = LastLocation(location)
    }
}