package com.baseprovider

import com.baseprovider.core.PosterResizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterResizerTest {

    @Test
    fun `empty template returns original url`() {
        val url = "https://site.com/poster.jpg"
        assertEquals(url, PosterResizer.resize(url, ""))
    }

    @Test
    fun `template without token returns original url`() {
        val url = "https://site.com/poster.jpg"
        assertEquals(url, PosterResizer.resize(url, "https://proxy/?w=342"))
    }

    @Test
    fun `null url returns null even with template`() {
        assertNull(PosterResizer.resize(null, "https://proxy/?url={url}&w=342"))
    }

    @Test
    fun `blank url returns blank`() {
        assertEquals("", PosterResizer.resize("", "https://proxy/?url={url}&w=342"))
    }

    @Test
    fun `template replaces url token with encoded original`() {
        val url = "https://site.com/a b.jpg"
        val out = PosterResizer.resize(url, "https://proxy/?url={url}&w=342&output=avif,webp")
        assertEquals(
            "https://proxy/?url=https%3A%2F%2Fsite.com%2Fa%20b.jpg&w=342&output=avif,webp",
            out
        )
    }

    @Test
    fun `template applies to simple url`() {
        val url = "https://site.com/poster.jpg"
        val out = PosterResizer.resize(url, "https://proxy/?url={url}&w=342&output=webp")
        assertEquals(
            "https://proxy/?url=https%3A%2F%2Fsite.com%2Fposter.jpg&w=342&output=webp",
            out
        )
    }

    @Test
    fun `url with existing query params is encoded as single value`() {
        val url = "https://cdn.com/img.jpg?w=780&h=1000"
        val out = PosterResizer.resize(url, "https://proxy/?url={url}")
        assertEquals(
            "https://proxy/?url=https%3A%2F%2Fcdn.com%2Fimg.jpg%3Fw%3D780%26h%3D1000",
            out
        )
    }
}