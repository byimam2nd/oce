package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

class Ultrahd : ExtractorApi() {
    override var name = "Ultrahd"
    override var mainUrl = "https://ultrahd.to"
    override val requiresReferer = true
}
