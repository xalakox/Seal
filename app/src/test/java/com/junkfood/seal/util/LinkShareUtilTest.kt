package com.junkfood.seal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkShareUtilTest {
    @Test
    fun normalizeServerUrl_acceptsHttpAndHttpsAndStripsTrailingSlash() {
        assertEquals(
            "https://linkshare.example.com",
            LinkShareUtil.normalizeServerUrl("  https://linkshare.example.com/  "),
        )
        assertEquals(
            "http://localhost:8080",
            LinkShareUtil.normalizeServerUrl("http://localhost:8080///"),
        )
    }

    @Test
    fun normalizeServerUrl_rejectsBlankAndNonHttpSchemes() {
        assertNull(LinkShareUtil.normalizeServerUrl(""))
        assertNull(LinkShareUtil.normalizeServerUrl("   "))
        assertNull(LinkShareUtil.normalizeServerUrl("linkshare.example.com"))
        assertNull(LinkShareUtil.normalizeServerUrl("ftp://linkshare.example.com"))
    }

    @Test
    fun parseUploadResponse_decodesLinkSharePayload() {
        val raw =
            """
            {
              "id": "AbCdEf1234",
              "share_url": "https://linkshare.example.com/v/AbCdEf1234",
              "media_url": "https://linkshare.example.com/media/AbCdEf1234.mp4",
              "thumb_url": "https://linkshare.example.com/thumb/AbCdEf1234.jpg",
              "title": "Sample Clip",
              "expires_at": "2026-12-25T00:00:00Z",
              "extra_ignored_field": 123
            }
            """.trimIndent()

        val parsed = LinkShareUtil.parseUploadResponse(raw)
        assertEquals("AbCdEf1234", parsed.id)
        assertEquals("https://linkshare.example.com/v/AbCdEf1234", parsed.shareUrl)
        assertEquals("https://linkshare.example.com/media/AbCdEf1234.mp4", parsed.mediaUrl)
        assertEquals("Sample Clip", parsed.title)
    }

    @Test
    fun canonicalizeSourceUrl_normalizesInstagramTrackingParams() {
        assertEquals(
            "https://instagram.com/reel/DgIsIt8RMga",
            LinkShareUtil.canonicalizeSourceUrl(
                "https://www.instagram.com/reel/DgIsIt8RMga/?lqvp=OXRyZWZzNXIxMmVo"
            ),
        )
        assertEquals(
            "https://instagram.com/reel/DgIsIt8RMga",
            LinkShareUtil.canonicalizeSourceUrl(
                "https://instagram.com/reels/DgIsIt8RMga/?igsh=abc123"
            ),
        )
    }
}
