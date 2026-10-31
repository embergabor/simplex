package com.ember.simpleplex.plex

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourcesTest {

    private val json = """
        [
          {"name": "Mine", "provides": "server", "clientIdentifier": "A", "owned": true, "accessToken": "ta",
           "connections": [
             {"uri": "https://relay.plex.direct:8443", "relay": true},
             {"uri": "https://1-2-3-4.x.plex.direct:32400", "address": "1.2.3.4", "local": false},
             {"uri": "https://192-168-1-5.x.plex.direct:32400/", "address": "192.168.1.5", "port": 32400, "local": true}
           ]},
          {"name": "Bob's", "provides": "server", "clientIdentifier": "B", "owned": 0, "accessToken": "tb", "connections": []},
          {"name": "Phone", "provides": "player", "clientIdentifier": "P"}
        ]
    """.trimIndent()

    @Test fun listsServersOnlyWithTheirTokens() {
        val servers = PlexClient.parseResources(json, "account")
        assertEquals(listOf("A", "B"), servers.map { it.machineId })
        assertEquals(listOf(true, false), servers.map { it.owned })
        assertEquals(listOf("ta", "tb"), servers.map { it.accessToken })
    }

    @Test fun localFirstThenRemoteThenRelay() {
        assertEquals(
            listOf(
                "https://192-168-1-5.x.plex.direct:32400",
                "http://192.168.1.5:32400",
                "https://1-2-3-4.x.plex.direct:32400",
                "https://relay.plex.direct:8443",
            ),
            PlexClient.parseResources(json, "account").first().uris,
        )
    }
}
