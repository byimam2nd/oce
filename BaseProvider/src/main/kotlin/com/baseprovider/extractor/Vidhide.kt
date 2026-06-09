package com.baseprovider.extractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*

class Vidhide : ExtractorApi() {
    override var name = "Vidhide"
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = true
}
