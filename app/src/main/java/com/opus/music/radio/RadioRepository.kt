package com.opus.music.radio

import com.opus.music.network.InternetRadioStation
import com.opus.music.network.SubsonicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Internet radio stations from the server (Navidrome: getInternetRadioStations). */
class RadioRepository(private val api: () -> SubsonicApi?) {
    suspend fun stations(): List<InternetRadioStation> = withContext(Dispatchers.IO) {
        try {
            api()?.getInternetRadioStations()
                ?.response?.internetRadioStations?.internetRadioStation
                .orEmpty()
        } catch (_: Exception) { emptyList() }
    }
}
