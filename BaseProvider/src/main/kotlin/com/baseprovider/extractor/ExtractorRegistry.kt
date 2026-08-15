package com.baseprovider.extractor
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

object ProviderExtractors {
    val list = listOf(
        Odnoklassniki(), Rumble(), StreamRuby(), Svanila(),
            Svilla(),
        ByseSX(), Hownetwork(), Cloudhownetwork(),
        PlayStreamplay(), AnichinStream(), AbyssPlayer(), Filedon(),
            BloggerVideo(),
        Wishfast(),
        Minochinos(), ShortIcu(), PlayPutarIn(), StreamHG(),
        Morencius(),
        MegaPlay(), AWSStream(), LuluStream(), Dhcplay(), Voe(), Xtwap(),
            Gdplayer(), Vidguardto2(), Movearnpre(),
        Lk21PlayerPage(), VideoNodePage(), Dailymotion(),
        PlayCdn(), EmTurbovid()
    )

    private val normalizedList: List<Pair<String, ExtractorApi>> = list
        .map {
        it.mainUrl.normalizeExtractorDomain() to it
    }

    fun getMatchingExtractors(url: String): List<ExtractorApi> {
        val urlDomain = url.normalizeDomain()
        return normalizedList.filter { (domain, _) -> urlDomain == domain
            || urlDomain.endsWith(".$domain") }.map { it.second }
    }

    fun hasMatchingExtractor(url: String): Boolean {
        val urlDomain = url.normalizeDomain()
        return normalizedList.any { (domain, _) -> urlDomain == domain
            || urlDomain.endsWith(".$domain") }
    }

    fun filtered(allowed: Set<String>): List<ExtractorApi> {
        if (allowed.isEmpty()) return list
        return list.filter { it.javaClass.simpleName in allowed || it
            .name in allowed || it.mainUrl in allowed }
    }
}
