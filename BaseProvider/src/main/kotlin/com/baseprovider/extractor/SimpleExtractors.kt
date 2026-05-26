package com.baseprovider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*


class Ultrahd : ExtractorApi() { override var name = "Ultrahd"; override var mainUrl = "https://ultrahd.to"; override val requiresReferer = true }
class Vtbe : ExtractorApi() { override var name = "Vtbe"; override var mainUrl = "https://vtbe.com"; override val requiresReferer = true }
class Vidhide : ExtractorApi() { override var name = "Vidhide"; override var mainUrl = "https://vidhide.com"; override val requiresReferer = true }
