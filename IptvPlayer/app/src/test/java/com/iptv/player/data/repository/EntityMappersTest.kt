package com.iptv.player.data.repository

import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.SourceType
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.KululuEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EntityMappersTest {
    /** Reversible stand-in codec: "enc:" prefix marks an encrypted envelope. */
    private val codec: SecureValueCodec = mock(SecureValueCodec::class.java).also { m ->
        `when`(m.encrypt(anyString())).thenAnswer { "enc:" + it.arguments[0] }
        `when`(m.decrypt(anyString())).thenAnswer { (it.arguments[0] as String).removePrefix("enc:") }
    }
    private val mappers = object : EntityMappers {
        override val secureValues: SecureValueCodec = codec
    }

    @Test
    fun `channel round trip encrypts the stream url and classifies radio by category name`() = with(mappers) {
        val radio = Channel(id = "xt_live_1", name = "Jazz FM", streamUrl = "http://s/1.ts", categoryName = "RADYO Kanallari")
        val entity = radio.toEntity()
        assertEquals("enc:http://s/1.ts", entity.streamUrl)
        assertTrue(entity.isRadio)
        val back = entity.toModel(isFav = true)
        assertEquals("http://s/1.ts", back.streamUrl)
        assertTrue(back.isFavorite)
        assertTrue(back.isRadio)

        val tv = Channel(id = "xt_live_2", name = "News", streamUrl = "http://s/2.ts", categoryName = "News")
        assertFalse(tv.toEntity().isRadio)
        val vodTyped = Channel(id = "v", name = "Radio Days", streamUrl = "u", categoryName = "Radio", type = ContentType.VOD)
        assertFalse(vodTyped.toEntity().isRadio)
    }

    @Test
    fun `legacy first party asset urls are upgraded on read`() = with(mappers) {
        val entity = ChannelEntity(
            id = "c", name = "C", streamUrl = "enc:http://kululu.live:8080/live/1.ts",
            logoUrl = "http://kululu.live/logo.png", categoryId = null, categoryName = null,
            epgChannelId = null, number = null, type = ContentType.LIVE.name,
        )
        val model = entity.toModel()
        assertEquals("${KululuEndpoint.HTTPS_SERVER_URL}/live/1.ts", model.streamUrl)
        assertEquals("${KululuEndpoint.HTTPS_SERVER_URL}/logo.png", model.logoUrl)
    }

    @Test
    fun `profile mapping decrypts credentials and tolerates an unknown source type`() = with(mappers) {
        val entity = ProfileEntity(
            id = 3, name = "Home", sourceType = "LEGACY", serverUrl = "enc:http://x",
            username = "enc:user", password = "enc:pass", m3uUrl = "", lockAdult = true, createdAt = 1L,
        )
        val profile = entity.toModel()
        assertEquals(SourceType.XTREAM, profile.config.type)
        assertEquals("http://x", profile.config.serverUrl)
        assertEquals("user", profile.config.username)
        assertEquals("pass", profile.config.password)
        assertTrue(profile.lockAdult)
    }

    @Test
    fun `saved category order ranks known ids first and appends newcomers in default order`() {
        val cats = listOf("a", "b", "c", "d").map { Category(it, it, ContentType.LIVE) }
        val ordered = applyCategoryOrder(cats, listOf("c", "a"))
        assertEquals(listOf("c", "a", "b", "d"), ordered.map { it.id })
        assertEquals(cats, applyCategoryOrder(cats, emptyList()))
    }

    @Test
    fun `epg id and name normalization are case and whitespace insensitive`() {
        assertEquals("bbc.one", normalizeEpgId("  BBC.One "))
        assertEquals(null, normalizeEpgId("   "))
        assertEquals("bbc one hd", normalizeName("  BBC   One\tHD "))
        assertEquals("", normalizeName(null))
    }
}
