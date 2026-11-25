package com.iptv.player.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iptv.player.security.PolicyPublicKey
import com.iptv.player.security.PolicySignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the file every installed app reads (remote-config.json on main): a
 * policy that this build would not accept, or a rule it would drop, fails the
 * pull request instead of being ignored silently in the field.
 */
class RemoteConfigFileTest {

    // Unit tests run in the app module directory; the file is at the repository root.
    private val root: JsonObject =
        JsonParser.parseString(File("../../remote-config.json").readText(Charsets.UTF_8)).asJsonObject

    @Test
    fun `the published policy is signed by the key this build trusts and every rule parses`() {
        assertEquals(1, root.get("schema").asInt)
        val signed = root.getAsJsonObject("playbackPolicySigned") ?: return
        val payload = signed.get("payload").asString
        assertEquals(
            payload,
            PolicySignature.acceptedPayload(PolicyPublicKey.pem, payload, signed.get("sig").asString, null),
        )
        val policy = JsonParser.parseString(payload).asJsonObject
        assertTrue(policy.get("expiresAtEpochMs").asLong > 0L)
        policy.getAsJsonArray("deviceOverrides")?.forEach { element ->
            val rule = element.asJsonObject
            val parsed = DeviceOverrideMatcher.parseRule(
                DeviceOverrideMatcher.RawRule(scalars(rule.getAsJsonObject("match")), scalars(rule.getAsJsonObject("set"))),
            )
            assertNotNull("rule would be ignored by the app: $rule", parsed)
        }
    }

    @Test
    fun `an announcement has an id and a text`() {
        val announcement = root.getAsJsonObject("announcement") ?: return
        assertTrue(announcement.get("id").asLong > 0L)
        assertTrue(announcement.get("message").asString.isNotBlank())
    }

    private fun scalars(obj: JsonObject?): Map<String, Any> = buildMap {
        obj?.entrySet()?.forEach { (key, value) ->
            val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@forEach
            put(
                key,
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                },
            )
        }
    }
}
