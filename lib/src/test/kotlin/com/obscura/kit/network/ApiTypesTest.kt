package com.obscura.kit.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The request DTOs' toJson() is the wire contract with obscura-server. A
 * renamed key or a wrongly-included optional silently breaks registration,
 * login, or device provisioning against a server we can't see in unit tests.
 * These tests pin the exact JSON shape the server expects.
 */
class ApiTypesTest {

    @Test
    fun `RegisterUserRequest serializes username and password`() {
        val json = RegisterUserRequest("alice", "hunter2hunter2").toJson()
        assertEquals("alice", json.getString("username"))
        assertEquals("hunter2hunter2", json.getString("password"))
        assertEquals(2, json.length())
    }

    @Test
    fun `LoginRequest omits deviceId when null`() {
        val json = LoginRequest("alice", "pw-goes-here").toJson()
        assertEquals("alice", json.getString("username"))
        assertEquals("pw-goes-here", json.getString("password"))
        assertFalse(json.has("deviceId"))
    }

    @Test
    fun `LoginRequest includes deviceId when present`() {
        val json = LoginRequest("alice", "pw-goes-here", deviceId = "dev-42").toJson()
        assertTrue(json.has("deviceId"))
        assertEquals("dev-42", json.getString("deviceId"))
    }

    @Test
    fun `RefreshTokenRequest and LogoutRequest carry the refresh token`() {
        assertEquals("rt-1", RefreshTokenRequest("rt-1").toJson().getString("refreshToken"))
        assertEquals("rt-2", LogoutRequest("rt-2").toJson().getString("refreshToken"))
    }

    @Test
    fun `SignedPreKeyJson serializes all three fields`() {
        val json = SignedPreKeyJson(keyId = 7, publicKey = "pub", signature = "sig").toJson()
        assertEquals(7, json.getInt("keyId"))
        assertEquals("pub", json.getString("publicKey"))
        assertEquals("sig", json.getString("signature"))
    }

    @Test
    fun `OneTimePreKeyJson serializes keyId and publicKey`() {
        val json = OneTimePreKeyJson(keyId = 3, publicKey = "pub3").toJson()
        assertEquals(3, json.getInt("keyId"))
        assertEquals("pub3", json.getString("publicKey"))
        assertFalse(json.has("signature"))
    }

    @Test
    fun `toJsonArray preserves order and contents of one-time prekeys`() {
        val array = listOf(
            OneTimePreKeyJson(1, "a"),
            OneTimePreKeyJson(2, "b"),
        ).toJsonArray()

        assertEquals(2, array.length())
        assertEquals(1, array.getJSONObject(0).getInt("keyId"))
        assertEquals("b", array.getJSONObject(1).getString("publicKey"))
    }

    @Test
    fun `ProvisionDeviceRequest nests signed prekey and one-time prekeys`() {
        val json = ProvisionDeviceRequest(
            name = "Pixel",
            identityKey = "idk",
            registrationId = 100,
            signedPreKey = SignedPreKeyJson(1, "spk", "sig"),
            oneTimePreKeys = listOf(OneTimePreKeyJson(2, "otk"))
        ).toJson()

        assertEquals("Pixel", json.getString("name"))
        assertEquals("idk", json.getString("identityKey"))
        assertEquals(100, json.getInt("registrationId"))
        assertEquals(1, json.getJSONObject("signedPreKey").getInt("keyId"))
        assertEquals(1, json.getJSONArray("oneTimePreKeys").length())
        assertEquals(2, json.getJSONArray("oneTimePreKeys").getJSONObject(0).getInt("keyId"))
    }

    @Test
    fun `UploadDeviceKeysRequest serializes the takeover key bundle`() {
        val json = UploadDeviceKeysRequest(
            identityKey = "idk",
            registrationId = 55,
            signedPreKey = SignedPreKeyJson(9, "spk", "sig"),
            oneTimePreKeys = listOf(OneTimePreKeyJson(10, "otk"), OneTimePreKeyJson(11, "otk2"))
        ).toJson()

        assertFalse(json.has("name"))
        assertEquals("idk", json.getString("identityKey"))
        assertEquals(55, json.getInt("registrationId"))
        assertEquals(9, json.getJSONObject("signedPreKey").getInt("keyId"))
        assertEquals(2, json.getJSONArray("oneTimePreKeys").length())
    }
}
