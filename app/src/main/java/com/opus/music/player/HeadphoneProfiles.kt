package com.opus.music.player

/**
 * AutoEQ-derived headphone correction profiles, downsampled to the app's
 * 5 EQ bands (60 / 230 / 910 / 3600 / 14000 Hz).
 *
 * Source curves: jaakkopasanen/AutoEq on GitHub (oratory1990 and crinacle
 * measurements), GraphicEQ results interpolated at the 5 band centers and
 * mean-normalized so each profile is a pure tonal correction (no overall
 * loudness change — use the preamp if you want it louder). Values are
 * millibels, same units as [EqualizerEngine].
 */
object HeadphoneProfiles {

    data class Profile(val name: String, val bandsMb: List<Int>)

    val profiles = listOf(
        Profile("Sony WH-1000XM5", listOf(-259,-390,220,-61,491)),
        Profile("Sony WH-1000XM4", listOf(-258,-227,208,227,51)),
        Profile("Bose QuietComfort 45", listOf(-51,-48,204,146,-251)),
        Profile("Apple AirPods Max", listOf(-83,-159,-82,504,-180)),
        Profile("Sennheiser HD 600", listOf(413,-153,-14,-109,-136)),
        Profile("Sennheiser HD 650", listOf(341,-218,-52,-74,3)),
        Profile("Apple AirPods Pro 2", listOf(-103,-255,-8,80,287)),
        Profile("Sony WF-1000XM4", listOf(-202,-413,-102,141,577)),
        Profile("Samsung Galaxy Buds2 Pro", listOf(79,33,158,-90,-181)),
        Profile("Sennheiser Momentum True Wireless 3", listOf(-55,-118,55,526,-408)),
    )
}
