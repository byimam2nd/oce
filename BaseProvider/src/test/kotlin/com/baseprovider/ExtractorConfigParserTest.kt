package com.baseprovider

import com.baseprovider.config.ExtractorConfig
import com.baseprovider.config.ExtractorStep
import com.baseprovider.config.fromExtractorJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtractorConfigParserTest {

    @Test
    fun `parses minimal config with defaults`() {
        val json = JSONObject("""{"id": "Test", "mainUrl": "https://test.com", "steps": [{"step": "constructUrl", "template": "{mainUrl}/hls/{id}.m3u8"}]}""")
        val config = fromExtractorJson("Test", json)
        assertEquals("Test", config.id)
        assertEquals("Test", config.name)
        assertEquals("https://test.com", config.mainUrl)
        assertTrue(config.requiresReferer)
        assertFalse(config.cached)
        assertNull(config.idSource)
        assertEquals(1, config.variants.size)
        assertEquals("default", config.variants[0].name)
        assertEquals(1, config.steps.size)
        assertEquals("adaptive", config.outputFilter)
        assertEquals("", config.videoReferer)
    }

    @Test
    fun `parses variants with headers and referer`() {
        val json = JSONObject("""{
            "id": "V",
            "mainUrl": "https://v.com",
            "requiresReferer": false,
            "cached": true,
            "variants": [
                {"name": "bare", "headers": {"Accept": "*/*"}},
                {"name": "origin", "headers": {"Origin": "https://v.com"}, "referer": "{mainUrl}/", "userAgent": "CustomUA"}
            ],
            "steps": [{"step": "regex", "pattern": "(m3u8)", "filter": ".m3u8", "universal": true}]
        }""")
        val config = fromExtractorJson("V", json)
        assertFalse(config.requiresReferer)
        assertTrue(config.cached)
        assertEquals(2, config.variants.size)
        assertEquals("bare", config.variants[0].name)
        assertEquals(mapOf("Accept" to "*/*"), config.variants[0].headers)
        assertEquals("", config.variants[0].referer)
        assertEquals("origin", config.variants[1].name)
        assertEquals("{mainUrl}/", config.variants[1].referer)
        assertEquals("CustomUA", config.variants[1].userAgent)
        val step = config.steps[0] as ExtractorStep.Regex
        assertTrue(step.universal)
        assertEquals(".m3u8", step.filter)
    }

    @Test
    fun `parses idSource query`() {
        val json = JSONObject("""{"id": "Q", "mainUrl": "https://q.com", "idSource": {"type": "query", "param": "id"}, "steps": [{"step": "fetch", "url": "{url}"}]}""")
        val config = fromExtractorJson("Q", json)
        val idSource = config.idSource!!
        assertEquals("query", idSource.type)
        assertEquals("id", idSource.param)
    }

    @Test
    fun `parses all step types`() {
        val stepsArr = JSONArray()
        stepsArr.put(JSONObject("""{"step": "fetch", "url": "{url}", "referer": "{mainUrl}/", "headers": {"X-Requested-With": "XMLHttpRequest"}, "store": "page"}"""))
        stepsArr.put(JSONObject("""{"step": "postForm", "url": "{mainUrl}/dl", "data": {"op": "embed", "file_code": "{id}"}, "store": "form"}"""))
        stepsArr.put(JSONObject("""{"step": "postJson", "url": "{mainUrl}/api", "jsonBody": "{\"text\":\"{id}\"}", "store": "json"}"""))
        stepsArr.put(JSONObject("""{"step": "regex", "pattern": "\"file\":\"([^\"]+)\"", "group": 1, "source": "page"}"""))
        stepsArr.put(JSONObject("""{"step": "jsonPath", "path": "sources[0].file", "source": "json"}"""))
        stepsArr.put(JSONObject("""{"step": "constructUrl", "template": "{mainUrl}/hls/{id}.m3u8"}"""))
        stepsArr.put(JSONObject("""{"step": "substring", "startMarker": "var urlPlay = '", "endMarker": "'", "source": "page"}"""))
        stepsArr.put(JSONObject("""{"step": "resolveUrl", "base": "{url}", "source": "cdn"}"""))
        stepsArr.put(JSONObject("""{"step": "packedJs", "source": "response", "store": "decoded"}"""))
        stepsArr.put(JSONObject("""{"step": "aesGcm", "source": "response", "keyPartsPath": "playback.key_parts", "ivPath": "playback.iv", "payloadPath": "playback.payload", "store": "plain"}"""))
        stepsArr.put(JSONObject("""{"step": "rhinoEval", "source": "script", "objectName": "svg", "store": "jsonResult"}"""))
        stepsArr.put(JSONObject("""{"step": "xorSig", "source": "watchlink", "store": "sig"}"""))

        val json = JSONObject()
        json.put("id", "S")
        json.put("mainUrl", "https://s.com")
        json.put("steps", stepsArr)

        val config = fromExtractorJson("S", json)
        assertEquals(12, config.steps.size)
        assertTrue(config.steps[0] is ExtractorStep.Fetch)
        assertTrue(config.steps[1] is ExtractorStep.PostForm)
        assertTrue(config.steps[2] is ExtractorStep.PostJson)
        assertTrue(config.steps[3] is ExtractorStep.Regex)
        assertTrue(config.steps[4] is ExtractorStep.JsonPath)
        assertTrue(config.steps[5] is ExtractorStep.ConstructUrl)
        assertTrue(config.steps[6] is ExtractorStep.Substring)
        assertTrue(config.steps[7] is ExtractorStep.ResolveUrl)
        assertTrue(config.steps[8] is ExtractorStep.PackedJs)
        assertTrue(config.steps[9] is ExtractorStep.AesGcm)
        assertTrue(config.steps[10] is ExtractorStep.RhinoEval)
        assertTrue(config.steps[11] is ExtractorStep.XorSig)

        val postForm = config.steps[1] as ExtractorStep.PostForm
        assertEquals(mapOf("op" to "embed", "file_code" to "{id}"), postForm.data)
        assertEquals("form", postForm.store)

        val jsonPath = config.steps[4] as ExtractorStep.JsonPath
        assertEquals("sources[0].file", jsonPath.path)

        val packedJs = config.steps[8] as ExtractorStep.PackedJs
        assertEquals("decoded", packedJs.store)
        val aesGcm = config.steps[9] as ExtractorStep.AesGcm
        assertEquals("playback.key_parts", aesGcm.keyPartsPath)
        assertEquals("playback.iv", aesGcm.ivPath)
        assertEquals("playback.payload", aesGcm.payloadPath)
        assertEquals("plain", aesGcm.store)
        val rhino = config.steps[10] as ExtractorStep.RhinoEval
        assertEquals("svg", rhino.objectName)
        assertEquals("jsonResult", rhino.store)
        val xorSig = config.steps[11] as ExtractorStep.XorSig
        assertEquals("sig", xorSig.store)
    }

    @Test
    fun `unknown step type is skipped with warning`() {
        val stepsArr = JSONArray()
        stepsArr.put(JSONObject("""{"step": "unknownMagic"}"""))
        stepsArr.put(JSONObject("""{"step": "constructUrl", "template": "{mainUrl}/x.mp4"}"""))
        val json = JSONObject()
        json.put("id", "U")
        json.put("mainUrl", "https://u.com")
        json.put("steps", stepsArr)
        val config = fromExtractorJson("U", json)
        assertEquals(1, config.steps.size)
        assertTrue(config.steps[0] is ExtractorStep.ConstructUrl)
    }

    @Test
    fun `empty variants defaults to single default variant`() {
        val json = JSONObject("""{"id": "E", "mainUrl": "https://e.com", "variants": [], "steps": [{"step": "constructUrl", "template": "{mainUrl}/x.mp4"}]}""")
        val config = fromExtractorJson("E", json)
        assertEquals(1, config.variants.size)
        assertEquals("default", config.variants[0].name)
    }

    @Test
    fun `all bundled extractor configs parse without unknown keys`() {
        val bundledFiles = listOf(
            "AnichinStream", "EmTurbovid", "Rumble", "Voe",
            "AWSStream", "Hownetwork", "Cloudhownetwork", "PlayCdn",
            "MegaPlay", "Gdplayer", "Dailymotion", "LuluStream",
            "Odnoklassniki", "Filedon", "Xtwap",
            "StreamRuby", "Svanila", "Svilla", "Movearnpre",
            "Minochinos", "Morencius", "Wishfast", "AbyssPlayer",
            "ByseSX", "Vidguardto2"
        )
        for (fileName in bundledFiles) {
            val stream = this::class.java.classLoader?.getResourceAsStream("extractors/$fileName.json")
                ?: throw AssertionError("Bundled extractor resource missing: $fileName.json")
            val jsonStr = stream.bufferedReader().readText()
            val json = JSONObject(jsonStr)
            val id = json.optString("id", fileName)
            fromExtractorJson(id, json)
        }
    }
}
