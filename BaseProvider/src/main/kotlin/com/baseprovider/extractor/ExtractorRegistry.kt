package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

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
        val urlDomain = url.normalizeDomain()
        return list.any { extractor -> urlDomain.contains(extractor.mainUrl.normalizeExtractorDomain()) }
    }
}
