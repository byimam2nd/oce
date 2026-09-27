package com.baseprovider.collector

import com.baseprovider.config.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class FallbackPipelineTest {

    private val pipeline = FallbackPipeline(
        ProviderConfig(id = "TestProvider")
    )

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `embed code iframe huruf kecil ter-decode ke src`() = runBlocking {
        val raw = b64("""<iframe src="https://dood.to/e/abc123" width="100%"></iframe>""")
        assertEquals("https://dood.to/e/abc123", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `embed code iframe huruf kapital ter-decode ke src`() = runBlocking {
        val raw = b64(
            "<IFRAME SRC=\"https://morencius.com/embed/gjyusjb6sjrd\" " +
                "FRAMEBORDER=0 WIDTH=640 HEIGHT=360 allowfullscreen></IFRAME>"
        )
        assertEquals("https://morencius.com/embed/gjyusjb6sjrd", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `embed code iframe kapital di dalam div tetap ter-decode`() = runBlocking {
        val raw = b64(
            "<div style=\"position:relative\">" +
                "<IFRAME SRC=\"https://rubyvidhub.com/embed-mddp6uzp7c7u.html\" SCROLLING=NO></div>"
        )
        assertEquals("https://rubyvidhub.com/embed-mddp6uzp7c7u.html", pipeline.decodeRawLink(raw))
    }

    @Test
    fun `URL biasa diteruskan tanpa perubahan`() = runBlocking {
        assertEquals("https://ok.ru/videoembed/1", pipeline.decodeRawLink("https://ok.ru/videoembed/1"))
    }

    @Test
    fun `base64 non-iframe yang tidak dikenali menghasilkan string kosong`() = runBlocking {
        // Harus kosong agar FallbackPipeline mencatat INVALID_URL, bukan exception.
        assertEquals("", pipeline.decodeRawLink(b64("dnd+fxdp5037wmt=")))
    }

    @Test
    fun `base64 html tanpa iframe menghasilkan string kosong`() = runBlocking {
        assertEquals("", pipeline.decodeRawLink(b64("<div>no iframe here</div>")))
    }
}
