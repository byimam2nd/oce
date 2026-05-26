package com.baseprovider

object ProviderExtractors {
    val list = listOf(
        Dailymotion(), Odnoklassniki(), Rumble(), StreamRuby(), Svanila(), Svilla(),
        ByseSX(), Hownetwork(), Cloudhownetwork(),
        PlayStreamplay(), AnichinStream(), AbyssPlayer(), Filedon(), BloggerVideo(),
        Ultrahd(), Vtbe(), wishfast(),
        Minochinos(), Vidhide(), ShortIcu(), PlayPutarIn(), StreamHG(),
        MegaPlay(), AWSStream(), LuluStream(), Dhcplay(), Voe(), Xtwap(), Gdplayer(), Vidguardto2(), Movearnpre(),
        Lk21PlayerPage()
    )

    fun hasMatchingExtractor(url: String): Boolean {
        val urlDomain = url.removePrefix("http://").removePrefix("https://").split("/").first().lowercase()
        return list.any { extractor ->
            val extractorDomain = extractor.mainUrl.removePrefix("http://").removePrefix("https://").replace("www.", "").lowercase()
            urlDomain.contains(extractorDomain)
        }
    }
}
