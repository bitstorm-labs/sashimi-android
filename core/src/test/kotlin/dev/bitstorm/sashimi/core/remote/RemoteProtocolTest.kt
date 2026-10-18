package dev.bitstorm.sashimi.core.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteProtocolTest {
    // Frames below are the exact ones a live Jellyfin 12.2 server sent to a
    // throwaway session (issue #161), MessageIds included.

    @Test
    fun `ForceKeepAlive asks for a keep-alive at half the server's window`() {
        val frame = """{"MessageId": "92d33595914f468289ad321911a9ccaa", "Data": 60, "MessageType": "ForceKeepAlive"}"""
        assertEquals(Inbound.ForceKeepAlive(30_000), RemoteProtocol.parse(frame))
    }

    @Test
    fun `a ForceKeepAlive without a usable window falls back to Jellyfin's 60 s`() {
        assertEquals(Inbound.ForceKeepAlive(30_000), RemoteProtocol.parse("""{"MessageType":"ForceKeepAlive"}"""))
        assertEquals(Inbound.ForceKeepAlive(30_000), RemoteProtocol.parse("""{"MessageType":"ForceKeepAlive","Data":0}"""))
        assertEquals(Inbound.ForceKeepAlive(30_000), RemoteProtocol.parse("""{"MessageType":"ForceKeepAlive","Data":"x"}"""))
    }

    @Test
    fun `a tiny keep-alive window is floored so the client never spins`() {
        assertEquals(Inbound.ForceKeepAlive(5_000), RemoteProtocol.parse("""{"MessageType":"ForceKeepAlive","Data":2}"""))
    }

    @Test
    fun `Playstate Stop is a Stop command`() {
        val frame = """{"MessageId": "b98b5c34921d411f968ccf0d45d84b85", "Data": {"Command": "Stop"}, "MessageType": "Playstate"}"""
        assertEquals(Inbound.Command(RemoteCommand.Stop), RemoteProtocol.parse(frame))
    }

    @Test
    fun `DisplayMessage carries header, text and the string TimeoutMs`() {
        val frame =
            """{"MessageId": "cd4cbafc40cc4b3db6dc4ea3212a40ab", "Data": {"Name": "DisplayMessage", """ +
                """"ControllingUserId": "00000000000000000000000000000000", "Arguments": {"Header": "Sashimi", """ +
                """"Text": "probe hello", "TimeoutMs": "4000"}}, "MessageType": "GeneralCommand"}"""
        assertEquals(
            Inbound.Command(RemoteCommand.DisplayMessage(header = "Sashimi", text = "probe hello", timeoutMs = 4_000)),
            RemoteProtocol.parse(frame),
        )
    }

    @Test
    fun `undeclared commands the server pushes anyway are ignored`() {
        // Both were delivered live to a session that declared only DisplayMessage.
        val pause = """{"MessageId": "d52d", "Data": {"Command": "Pause"}, "MessageType": "Playstate"}"""
        val goHome = """{"MessageId": "f3c4", "Data": {"Name": "GoHome", "Arguments": {}}, "MessageType": "GeneralCommand"}"""
        assertEquals(Inbound.Ignored, RemoteProtocol.parse(pause))
        assertEquals(Inbound.Ignored, RemoteProtocol.parse(goHome))
    }

    @Test
    fun `only DisplayMessage is shown, not another command that carries text`() {
        val other = """{"MessageType":"GeneralCommand","Data":{"Name":"DisplayContent","Arguments":{"Header":"H","Text":"t"}}}"""
        val sendString = """{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"Text":"typed"}}}"""
        assertEquals(Inbound.Ignored, RemoteProtocol.parse(other))
        assertEquals(Inbound.Ignored, RemoteProtocol.parse(sendString))
    }

    @Test
    fun `other message types and malformed frames are ignored, never thrown`() {
        listOf(
            """{"MessageId": "afd4", "MessageType": "KeepAlive"}""",
            """{"MessageType":"Play","Data":{"ItemIds":["a"],"PlayCommand":"PlayNow"}}""",
            """{"MessageType":"UserDataChanged","Data":{"UserId":"u"}}""",
            """{"MessageType":"Playstate","Data":"Stop"}""",
            """{"MessageType":"Playstate"}""",
            """{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage"}}""",
            """{"MessageType":42}""",
            """{"Data":{"Command":"Stop"}}""",
            """[1,2]""",
            "not json",
            "",
        ).forEach { assertEquals(it, Inbound.Ignored, RemoteProtocol.parse(it)) }
    }

    private fun message(args: String): Inbound =
        RemoteProtocol.parse("""{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{$args}}}""")

    private fun timeoutOf(args: String): Long = ((message(args) as Inbound.Command).command as RemoteCommand.DisplayMessage).timeoutMs

    @Test
    fun `TimeoutMs is honoured as a string or a number`() {
        assertEquals(12_000, timeoutOf(""""Text":"t","TimeoutMs":"12000""""))
        assertEquals(12_000, timeoutOf(""""Text":"t","TimeoutMs":12000"""))
        assertEquals(12_000, timeoutOf(""""Text":"t","TimeoutMs":" 12000 """"))
    }

    @Test
    fun `a missing, zero, negative or garbage TimeoutMs gets the default`() {
        listOf("", ""","TimeoutMs":"0"""", ""","TimeoutMs":"-5"""", ""","TimeoutMs":"soon"""", ""","TimeoutMs":null""").forEach {
            assertEquals(it, RemoteProtocol.DEFAULT_MESSAGE_MS, timeoutOf(""""Text":"t"$it"""))
        }
    }

    @Test
    fun `TimeoutMs is clamped to a readable, bounded window`() {
        assertEquals(RemoteProtocol.MIN_MESSAGE_MS, timeoutOf(""""Text":"t","TimeoutMs":"50""""))
        assertEquals(RemoteProtocol.MAX_MESSAGE_MS, timeoutOf(""""Text":"t","TimeoutMs":"3600000""""))
    }

    @Test
    fun `a header-only message shows the header as its text`() {
        assertEquals(
            Inbound.Command(RemoteCommand.DisplayMessage(header = null, text = "Server restarting", timeoutMs = 5_000)),
            message(""""Header":"Server restarting","Text":"  """"),
        )
    }

    @Test
    fun `a blank header is dropped and text is trimmed`() {
        assertEquals(
            Inbound.Command(RemoteCommand.DisplayMessage(header = null, text = "hi", timeoutMs = 5_000)),
            message(""""Header":" ","Text":" hi """"),
        )
    }

    @Test
    fun `a message with neither header nor text is ignored`() {
        assertEquals(Inbound.Ignored, message(""""Header":"","Text":"""""))
        assertEquals(Inbound.Ignored, message(""""TimeoutMs":"4000""""))
    }

    @Test
    fun `capabilities declare media control and DisplayMessage and no cast target`() {
        val caps = RemoteProtocol.CAPABILITIES
        assertEquals(true, caps.supportsMediaControl)
        assertEquals(listOf("DisplayMessage"), caps.supportedCommands)
        assertEquals(emptyList<String>(), caps.playableMediaTypes)
    }
}
