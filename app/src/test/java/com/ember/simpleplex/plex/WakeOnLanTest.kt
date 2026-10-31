package com.ember.simpleplex.plex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {

    private val mac = byteArrayOf(0x20, 0x07, 0x11, 0x1a, 0x19, 0x86.toByte())

    @Test
    fun `MAC with any separators or none`() {
        for (text in listOf("20:07:11:1a:19:86", "20-07-11-1A-19-86", "2007111a1986", " 2007.111a.1986 ")) {
            assertArrayEquals(text, mac, WakeOnLan.parseMac(text))
        }
    }

    @Test
    fun `not a MAC`() {
        for (text in listOf("", "20:07:11:1a:19", "20:07:11:1a:19:86:00", "20:07:11:1a:19:8g")) {
            assertNull(text, WakeOnLan.parseMac(text))
        }
    }

    @Test
    fun formats() = assertEquals("20:07:11:1a:19:86", WakeOnLan.format(mac))

    @Test
    fun `packet is six ff bytes then the address 16 times`() {
        val packet = WakeOnLan.packet(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test
    fun targets() {
        assertEquals(listOf("255.255.255.255", "192.168.0.255"), WakeOnLan.targets("http://192.168.0.13:32400"))
        assertEquals(listOf("255.255.255.255"), WakeOnLan.targets("https://plex.example.com:32400"))
        assertEquals(listOf("255.255.255.255"), WakeOnLan.targets(null))
    }
}
