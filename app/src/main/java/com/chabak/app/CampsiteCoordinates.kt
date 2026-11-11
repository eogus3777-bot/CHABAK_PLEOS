package com.chabak.app

internal object CampsiteCoordinates {
    // GoCamping covers Korea. Disjoint ranges also reject reversed axes.
    fun from(latitude: Double, longitude: Double): TravelPoint? =
        if (latitude in 32.0..40.0 && longitude in 123.0..133.0) TravelPoint(latitude, longitude) else null

    fun forPlace(place: Place): TravelPoint? {
        place.permit?.let { return from(it.latitude, it.longitude) }
        return place.coordinates ?: GoCampingCatalog.coordinatesFor(place)
    }
}
