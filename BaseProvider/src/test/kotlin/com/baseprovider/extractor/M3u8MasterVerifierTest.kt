package com.baseprovider.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8MasterVerifierTest {

    private val malformedMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-ALLOW-CACHE:YES
        #EXT-X-STREAM-INF:BANDWIDTH=676000,RESOLUTION=640x360
        https://1a-1791.com/video/fww1/f6/s8/2/S/E/j/Q/SEjQA.baa.tar?r_file=chunklist.m3u8&r_range=111048704-111061607
        #EXT-X-STREAM-INF:BANDWIDTH=1052000,RESOLUTION=854x480
        https://1a-1791.com/video/fww1/f6/s8/2/S/E/j/Q/SEjQA.caa.tar?r_file=chunklist.m3u8&r_range=172357632-172370621
        #EXT-X-STREAM-INF:BANDWIDTH=2137000,RESOLUTION=1280x720
        https://1a-1791.com/video/fww1/f6/s8/2/S/E/j/Q/SEjQA.gaa.tar?r_file=chunklist.m3u8&r_range=348856320-348869388
        #EXT-X-STREAM-INF:BANDWIDTH=4096000,RESOLUTION=1920x1080

    """.trimIndent()

    private val cleanMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=676000,RESOLUTION=640x360
        /v/baa/360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1052000,RESOLUTION=854x480
        /v/caa/480.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2137000,RESOLUTION=1280x720
        /v/gaa/720.m3u8
    """.trimIndent()

    @Test
    fun `parse drops variant with empty uri`() {
        val v = M3u8MasterVerifier.parseVariants(malformedMaster)
        assertEquals(4, v.size)
        assertEquals(360, v[0].height)
        assertEquals(480, v[1].height)
        assertEquals(720, v[2].height)
        assertTrue(v[0].url != null)
        assertTrue(v[1].url != null)
        assertTrue(v[2].url != null)
        // 1080p (BANDWIDTH=4096000) punya URI kosong → url null
        assertEquals(1080, v[3].height)
        assertEquals(null, v[3].url)
    }

    @Test
    fun `parse handles plain media playlist`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg0.ts
            #EXTINF:10,
            seg1.ts
        """.trimIndent()
        assertEquals(0, M3u8MasterVerifier.parseVariants(media).size)
    }

    @Test
    fun `classify returns Valid dropping malformed variant`() {
        val masterUrl = "https://anichin.stream/hls/v7c3u7k.m3u8"
        val verdict = M3u8MasterVerifier.classify(
            masterUrl, M3u8MasterVerifier.parseVariants(malformedMaster)
        )
        assertTrue(verdict is M3u8MasterVerifier.Verdict.Valid)
        val variants = (verdict as M3u8MasterVerifier.Verdict.Valid).variants
        assertEquals(3, variants.size)
        // Tidak ada variant yang resolve ke master itu sendiri
        assertTrue(variants.none { it.first == masterUrl })
        // Height tetap terpasang sesuai RESOLUTION
        assertEquals(720, variants[2].second)
    }

    @Test
    fun `classify returns Clean for all-valid master`() {
        val masterUrl = "https://cdn.example.com/hls/master.m3u8"
        val verdict = M3u8MasterVerifier.classify(
            masterUrl, M3u8MasterVerifier.parseVariants(cleanMaster)
        )
        assertTrue(verdict is M3u8MasterVerifier.Verdict.Clean)
    }

    @Test
    fun `classify returns Clean for media playlist`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg0.ts
        """.trimIndent()
        assertTrue(
            M3u8MasterVerifier.classify(
                "https://cdn.example.com/hls/master.m3u8",
                M3u8MasterVerifier.parseVariants(media)
            ) is M3u8MasterVerifier.Verdict.Clean
        )
    }

    @Test
    fun `classify returns AllMalformed when no variant has uri`() {
        val broken = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4096000,RESOLUTION=1920x1080

            #EXT-X-STREAM-INF:BANDWIDTH=2137000,RESOLUTION=1280x720
        """.trimIndent()
        assertTrue(
            M3u8MasterVerifier.classify(
                "https://cdn.example.com/hls/master.m3u8",
                M3u8MasterVerifier.parseVariants(broken)
            ) is M3u8MasterVerifier.Verdict.AllMalformed
        )
    }

    @Test
    fun `classify treats self-referencing uri as malformed`() {
        val masterUrl = "https://cdn.example.com/hls/master.m3u8"
        val selfRef = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4096000,RESOLUTION=1920x1080
            https://cdn.example.com/hls/master.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2137000,RESOLUTION=1280x720
            /v/gaa/720.m3u8
        """.trimIndent()
        val verdict = M3u8MasterVerifier.classify(
            masterUrl, M3u8MasterVerifier.parseVariants(selfRef)
        )
        assertTrue(verdict is M3u8MasterVerifier.Verdict.Valid)
        val variants = (verdict as M3u8MasterVerifier.Verdict.Valid).variants
        assertEquals(1, variants.size)
        assertEquals(720, variants[0].second)
    }
}
