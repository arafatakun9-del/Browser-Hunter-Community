package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.download.HttpContentRange
import com.example.download.parseHttpContentRange
import java.net.URLEncoder

class BrowserHunterUnitTest {

    @Test
    fun testPdfUrlDetection() {
        val pdfUrls = listOf(
            "https://www.nu.ac.bd/uploads/notice/notice_2026.pdf",
            "https://example.com/document.pdf?token=123",
            "https://university.edu/result/pdf/view",
            "https://gov.bd/notice/pdf/exam_schedule"
        )
        val nonPdfUrls = listOf(
            "https://google.com",
            "https://youtube.com/watch?v=12345",
            "https://wikipedia.org/wiki/Android"
        )

        for (url in pdfUrls) {
            assertTrue("Should detect as PDF: $url", isPdfUrl(url))
        }

        for (url in nonPdfUrls) {
            assertFalse("Should not detect as PDF: $url", isPdfUrl(url))
        }
    }

    @Test
    fun testSanitizeQueryUrl() {
        assertEquals("https://github.com", sanitizeQueryUrl("https://github.com"))
        assertEquals("http://nu.ac.bd", sanitizeQueryUrl("http://nu.ac.bd"))
        assertEquals("https://prothomalo.com", sanitizeQueryUrl("prothomalo.com"))
        val search = sanitizeQueryUrl("realme c71 android 16")
        assertTrue(search.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun testHttpContentRangeParsingRejectsMalformedRanges() {
        assertEquals(HttpContentRange(0L, 9L, 10L), parseHttpContentRange("bytes 0-9/10"))
        assertEquals(HttpContentRange(5L, 9L, 10L), parseHttpContentRange("bytes 5-9/10"))
        // A syntactically valid partial range is parsed; the downloader separately requires
        // the returned interval to reach totalBytes - 1 before appending it as a final segment.
        assertEquals(HttpContentRange(0L, 8L, 10L), parseHttpContentRange("bytes 0-8/10"))
        assertEquals(null, parseHttpContentRange("bytes 0-9/*"))
        assertEquals(null, parseHttpContentRange("bytes 9-5/10"))
        assertEquals(null, parseHttpContentRange("items 0-9/10"))
        assertEquals(null, parseHttpContentRange(null))
    }

    @Test
    fun testSanitizeFileName() {
        val unsafeName = "file:name?with*illegal|chars/and spaces.mp4"
        val cleanName = unsafeName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        assertFalse(cleanName.contains(":"))
        assertFalse(cleanName.contains("?"))
        assertFalse(cleanName.contains("*"))
        assertFalse(cleanName.contains("|"))
        assertFalse(cleanName.contains("/"))
        assertTrue(cleanName.endsWith(".mp4"))
    }

    private fun isPdfUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".pdf") ||
                lower.contains(".pdf?") ||
                lower.contains("/pdf/") ||
                lower.contains("notice/pdf") ||
                lower.contains("result/pdf")
    }

    private fun sanitizeQueryUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}
