package com.baseprovider.extractor
import com.baseprovider.config.ExtractorConfig
import com.baseprovider.config.ExtractorConfigRegistry
import com.baseprovider.network.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

object ProviderExtractors {

    private val legacyList = listOf(
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
        PlayCdn(), EmTurbovid(),
        Krakenfiles(),
        VideoplayerVip(), Anonmp4(), AnichinPlayer()
    )

    /**
     * Id extractor yang sudah dimigrasi ke config-driven (file JSON di
     * `config/extractors/`). Tambahkan id ke set ini saat migrasi diuji.
     * Id di luar set ini memakai class legacy — non-breaking & bisa rollback:
     * jika config gagal load, extractor fallback ke class legacy.
     */
    private val configDrivenIds = setOf(
        "AnichinStream", "EmTurbovid", "Rumble", "Voe",
        "AWSStream", "Hownetwork", "Cloudhownetwork", "PlayCdn",
        "MegaPlay", "Gdplayer", "Dailymotion", "LuluStream",
        "Filedon", "Xtwap",
        "StreamRuby", "Svanila", "Svilla", "Movearnpre",
        "Minochinos", "Morencius", "Wishfast", "AbyssPlayer",
        "ByseSX", "Vidguardto2",
        "BloggerVideo", "PlayPutarIn", "Lk21PlayerPage",
        "VideoNodePage", "ShortIcu", "PlayStreamplay",
        "Dhcplay", "StreamHG",
        "Krakenfiles",
        "AnichinPlayer"
    )

    /**
     * Extractor TANPA class legacy — hidup murni dari file JSON di
     * `config/extractors/`. Tambahkan id baru di sini saat membuat extractor
     * config-driven yang belum punya stub Kotlin.
     */
    private val pureConfigIds = listOf(
        "EmbedPyrox",
        "VeevTo"
    )

    private fun buildList(): List<ExtractorApi> {
        val result = mutableListOf<ExtractorApi>()
        for (extractor in legacyList) {
            val id = extractor.javaClass.simpleName
            if (id in configDrivenIds) {
                val config: ExtractorConfig? = ExtractorConfigRegistry.get(id)
                if (config != null) {
                    result.add(ConfigDrivenExtractor(config))
                    continue
                }
            }
            result.add(extractor)
        }
        // Pure-config: tidak punya legacy fallback — JSON adalah satu-satunya
        // sumber kebenaran. Gagal load = dilewati (jangan bikin crash registry).
        for (id in pureConfigIds) {
            if (result.any { it.name.equals(id, true) || it.name.equals(
                    ExtractorConfigRegistry.get(id)?.name ?: "", true) }) continue
            val config = ExtractorConfigRegistry.get(id) ?: continue
            result.add(ConfigDrivenExtractor(config))
        }
        return result
    }

    val list: List<ExtractorApi> by lazy { buildList() }

    private val normalizedList: List<Pair<String, ExtractorApi>> by lazy {
        list.map { it.mainUrl.normalizeExtractorDomain() to it }
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