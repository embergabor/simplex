package com.ember.simpleplex.ui

import com.embergabor.simplex.core.plex.PlexStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class TroubleTest {
    @Test
    fun aRefusedSignInWhateverTheNetworkSays() {
        assertEquals(Trouble.SIGN_IN, Trouble.of(PlexStatus(401, "/library/sections"), online = true))
        assertEquals(Trouble.SIGN_IN, Trouble.of(PlexStatus(401, "/library/sections"), online = false))
    }

    @Test
    fun plexAnsweringWithAnErrorIsTheServersTrouble() {
        assertEquals(Trouble.SERVER, Trouble.of(PlexStatus(503, "/library/sections"), online = true))
        assertEquals(Trouble.SERVER, Trouble.of(PlexStatus(404, "/library/metadata/1"), online = true))
        // Not a network failure at all: an answer that couldn't be read.
        assertEquals(Trouble.SERVER, Trouble.of(NoSuchElementException("List is empty."), online = true))
    }

    @Test
    fun noAnswerIsTheTvOfflineOrTheServerUnreachable() {
        assertEquals(Trouble.OFFLINE, Trouble.of(ConnectException("Network is unreachable"), online = false))
        assertEquals(Trouble.UNREACHABLE, Trouble.of(ConnectException("failed to connect to /192.168.0.13"), online = true))
        assertEquals(Trouble.UNREACHABLE, Trouble.of(IOException("timeout"), online = true))
    }
}
