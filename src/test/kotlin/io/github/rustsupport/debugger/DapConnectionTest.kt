package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DapConnectionTest {
    @Test
    fun `framing reads what it writes, several messages in a row, non-ASCII by bytes`() {
        val bytes = DapFraming.frame("""{"a":1}""") + DapFraming.frame("""{"text":"привет"}""")
        val input = ByteArrayInputStream(bytes)
        assertEquals("""{"a":1}""", DapFraming.read(input))
        assertEquals("""{"text":"привет"}""", DapFraming.read(input))
        assertNull(DapFraming.read(input))
    }

    @Test
    fun `header names are not case sensitive and other headers are skipped`() {
        val input = ByteArrayInputStream("content-length: 2\r\nContent-Type: application/json\r\n\r\n{}".toByteArray())
        assertEquals("{}", DapFraming.read(input))
    }

    @Test
    fun `a message cut short is an error`() {
        try {
            DapFraming.read(ByteArrayInputStream("Content-Length: 10\r\n\r\n{}".toByteArray()))
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun `the error of the adapter fills its variables`() {
        val response = JsonParser.parseString("""{"success":false,"message":"x","body":{"error":{"format":"Cannot read {name}","variables":{"name":"v"}}}}""").asJsonObject
        assertEquals("Cannot read v", DapConnection.errorMessage(response))
        assertEquals("plain", DapConnection.errorMessage(JsonParser.parseString("""{"message":"plain"}""").asJsonObject))
    }

    @Test
    fun `requests are matched to responses by request_seq, events go to the listener`() {
        val toClient = PipedOutputStream()
        val clientInput = PipedInputStream(toClient, 65536)
        val sent = ByteArrayOutputStream()
        val events = LinkedBlockingQueue<String>()
        val connection = DapConnection(clientInput, sent, object : DapConnection.Listener {
            override fun event(event: String, body: JsonObject) {
                events.add(event)
            }
        })
        connection.start()
        val first = connection.request("initialize", json("adapterID" to "lldb"))
        val second = connection.request("threads")
        val written = ByteArrayInputStream(sent.toByteArray())
        val request1 = JsonParser.parseString(DapFraming.read(written)).asJsonObject
        val request2 = JsonParser.parseString(DapFraming.read(written)).asJsonObject
        assertEquals("initialize", request1.string("command"))
        assertEquals("lldb", request1.getAsJsonObject("arguments").string("adapterID"))
        // answers out of order, an event between them
        toClient.write(DapFraming.frame("""{"seq":1,"type":"response","request_seq":${request2.int("seq")},"success":false,"command":"threads","message":"busy"}"""))
        toClient.write(DapFraming.frame("""{"seq":2,"type":"event","event":"initialized"}"""))
        toClient.write(DapFraming.frame("""{"seq":3,"type":"response","request_seq":${request1.int("seq")},"success":true,"command":"initialize","body":{"supportsConfigurationDoneRequest":true}}"""))
        toClient.flush()
        assertEquals(true, first.get(5, TimeUnit.SECONDS).bool("supportsConfigurationDoneRequest"))
        try {
            second.get(5, TimeUnit.SECONDS)
            fail()
        } catch (e: ExecutionException) {
            assertTrue(e.cause is DapException)
            assertEquals("busy", e.cause!!.message)
        }
        assertEquals("initialized", events.poll(5, TimeUnit.SECONDS))
        connection.close()
        assertTrue(connection.request("threads").isCompletedExceptionally)
    }

    @Test
    fun `json leaves nulls out`() {
        assertEquals("""{"a":1,"c":["x"]}""", DapConnection.GSON.toJson(json("a" to 1, "b" to null, "c" to listOf("x"))))
    }
}
