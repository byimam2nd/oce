package com.baseprovider.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveQualityPickerTest {

    private val masterText = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1328291,QUALITY=sd,FRAME-RATE=25,RESOLUTION=852x480
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/2/sig/cDExW5Jjmtw/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=396756,QUALITY=lowest,FRAME-RATE=25,RESOLUTION=426x240
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/0/sig/nPu1t-xSnc4/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=811148,QUALITY=low,FRAME-RATE=25,RESOLUTION=640x360
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/1/sig/JzKy_CEsJVc/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2773522,QUALITY=hd,FRAME-RATE=25,RESOLUTION=1280x720
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/3/sig/vWroyYtwIqI/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=4737968,QUALITY=full,FRAME-RATE=25,RESOLUTION=1920x1080
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/5/sig/AflZVi2-xUs/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=197734,QUALITY=mobile,FRAME-RATE=25,RESOLUTION=256x144
        /expires/1786865344841/srcIp/114.5.109.226/pr/10/srcAg/CHROME/ch/-536166974/ms/95.142.206.131/type/4/sig/FXqO5NxNhi8/ct/8/urls/185.226.55.46%3B176.112.172.99/clientType/0/zs/43/id/2879024007858/video/
    """.trimIndent()

    private fun variants() = AdaptiveQualityPicker.parseVariants(masterText)

    @Test
    fun `parse six okru variants`() {
        val v = variants()
        assertEquals(6, v.size)
        assertEquals(1328291L, v[0].bandwidth)
        assertEquals(480, v[0].height)
        assertEquals(396756L, v[1].bandwidth)
        assertEquals(240, v[1].height)
        assertEquals(197734L, v[5].bandwidth)
        assertEquals(144, v[5].height)
    }

    @Test
    fun `parse returns empty for garbage`() {
        assertEquals(0, AdaptiveQualityPicker.parseVariants("not a playlist").size)
        assertEquals(0, AdaptiveQualityPicker.parseVariants("").size)
    }

    @Test
    fun `resolve absolute path against master origin`() {
        val master = "https://ok6-4.vkuser.net/video.m3u8?cmd=videoPlayerCdn&expires=1"
        val url = AdaptiveQualityPicker.resolveUrl(master, "/expires/1/type/2/video/")
        assertEquals("https://ok6-4.vkuser.net/expires/1/type/2/video/", url)
    }

    @Test
    fun `resolve relative segment against playlist dir`() {
        val playlist = "https://ok6-4.vkuser.net/expires/1/type/2/video/"
        val url = AdaptiveQualityPicker.resolveUrl(playlist, "MEDIUM00000.ts")
        assertEquals("https://ok6-4.vkuser.net/expires/1/type/2/video/MEDIUM00000.ts", url)
    }

    @Test
    fun `resolve keeps absolute url unchanged`() {
        val abs = "https://cdn.example.com/seg.ts"
        assertEquals(abs, AdaptiveQualityPicker.resolveUrl("https://x.test/a.m3u8", abs))
    }

    @Test
    fun `first segment skips comments`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            MEDIUM00000.ts
            #EXTINF:10,
            MEDIUM00001.ts
        """.trimIndent()
        assertEquals("MEDIUM00000.ts", AdaptiveQualityPicker.firstSegmentOf(playlist))
        assertNull(AdaptiveQualityPicker.firstSegmentOf("#EXTM3U\n#EXT-X-VERSION:3\n"))
    }

    @Test
    fun `choose highest variant that fits measured speed`() {
        val v = variants()
        // 500 KB/s = 4,000,000 bits/s; kapasitas = 3,200,000 -> hd (720p 2.77Mbps)
        val picked = AdaptiveQualityPicker.chooseVariant(v, 500_000.0)
        assertEquals(2773522L, picked?.bandwidth)
        assertEquals(720, picked?.height)
    }

    @Test
    fun `choose falls back to lowest when speed too low`() {
        val v = variants()
        // 50 KB/s = 400,000 bits/s -> tak ada yang muat -> varian terendah (mobile)
        val picked = AdaptiveQualityPicker.chooseVariant(v, 50_000.0)
        assertEquals(197734L, picked?.bandwidth)
        assertEquals(144, picked?.height)
    }

    @Test
    fun `choose supports fast connection`() {
        val v = variants()
        // 1 MB/s = 8,000,000 bits/s -> full 1080p (4.7Mbps)
        val picked = AdaptiveQualityPicker.chooseVariant(v, 1_000_000.0)
        assertEquals(4737968L, picked?.bandwidth)
        assertEquals(1080, picked?.height)
    }

    @Test
    fun `choose returns null on empty list`() {
        assertNull(AdaptiveQualityPicker.chooseVariant(emptyList(), 500_000.0))
    }
}