package com.cardroid.runner

import com.cardroid.lib.Iap2ControlSession
import com.cardroid.lib.Iap2Frame
import com.cardroid.lib.Iap2LinkSync
import com.cardroid.lib.airplay.IapOutResponder
import com.cardroid.mfi.MfiAuthProvider
import java.io.ByteArrayInputStream
import kotlin.test.*

/** car-touch-iap2-tunnel Stage 3 IAP_OUT responder state machine. */
class IapOutResponderTest {
    private val sent = mutableListOf<ByteArray>()
    private val logs = mutableListOf<String>()
    private val r = IapOutResponder({ sent += it }, { logs += it })
    private val marker = Iap2LinkSync.DETECT_MARKER
    private val lsp = byteArrayOf(
        1, 0x7F, 0x10, 0x00, 0x07, 0xD0.toByte(), 0x03, 0xE8.toByte(), 30, 1,
        1, 0, 2, 2, 2, 2,
    )

    private fun syn(seq: Int = 0x11, payload: ByteArray = lsp) =
        Iap2Frame(0x80.toByte(), seq.toByte(), 0, 0, payload).encode()

    private fun carData(
        seq: Int,
        ack: Int,
        id: Int,
        params: ByteArray = ByteArray(0),
        session: Int = 1,
    ): ByteArray = Iap2Frame(
        0x40,
        seq.toByte(),
        ack.toByte(),
        session.toByte(),
        Iap2ControlSession.build(id, params),
    ).encode()

    private fun frame(bytes: ByteArray): Iap2Frame =
        Iap2Frame.readFrom(ByteArrayInputStream(bytes)) ?: error("not a frame")

    private fun message(bytes: ByteArray): Pair<Int, ByteArray> =
        Iap2ControlSession.parse(frame(bytes).payload)?.let { it.first to Iap2ControlSession.firstParamValue(it.second) }
            ?: error("not a control message")

    private fun linkUp(responder: IapOutResponder = r) {
        responder.onData(syn())
        responder.onData(Iap2Frame(0x40, 0x11, 1, 0, ByteArray(0)).encode())
    }

    @Test fun markerIsEchoed() {
        r.onData(marker)
        assertEquals(1, sent.size); assertContentEquals(marker, sent[0])
        assertEquals(IapOutResponder.State.MARKER_ECHOED, r.state)
    }

    @Test fun synGetsSynAckEchoingLsp() {
        r.onData(marker); r.onData(syn())
        val f = frame(sent.last())
        assertEquals(0xC0, f.control.toInt() and 0xFF); assertEquals(1, f.seq.toInt())
        assertEquals(0x11, f.ack.toInt()); assertEquals(0, f.session.toInt())
        assertContentEquals(lsp, f.payload)
        assertEquals(IapOutResponder.State.SYNACK_SENT, r.state)
        r.onData(Iap2Frame(0x40, 0x11, 1, 0, ByteArray(0)).encode())
        assertEquals(IapOutResponder.State.LINK_UP, r.state)
    }

    @Test fun repeatedSynResendsStableSynAckSequence() {
        r.onData(syn())
        val first = frame(sent.single())
        r.onData(syn())
        val synAcks = sent.mapNotNull { runCatching { frame(it) }.getOrNull() }
            .filter { (it.control.toInt() and 0xC0) == 0xC0 }
        assertEquals(2, synAcks.size)
        assertTrue(synAcks.all { it.seq == first.seq })
    }

    @Test fun dataGetsBareAck() {
        linkUp()
        sent.clear()
        r.onData(carData(seq = 0x12, ack = 2, id = 0x1D00, params = ByteArray(0)))
        assertEquals(1, sent.size)
        val ack = frame(sent[0])
        assertEquals(0x40, ack.control.toInt() and 0xFF); assertEquals(0x12, ack.ack.toInt())
        assertEquals(9, sent[0].size)
        assertTrue(logs.any { it.contains("car msg 0x1D00") })
    }

    @Test fun markerPlusSynInOneChunk() {
        r.onData(marker + syn())
        assertEquals(2, sent.size)
        assertContentEquals(marker, sent[0]); assertEquals(0xC0, frame(sent[1]).control.toInt() and 0xFF)
    }

    @Test fun splitSynAcrossChunks() {
        val s = syn(); r.onData(s.copyOfRange(0, 5)); assertTrue(sent.isEmpty()); r.onData(s.copyOfRange(5, s.size))
        assertEquals(1, sent.size)
    }

    @Test fun markerAfterLinkUpNotEchoed() {
        linkUp(); sent.clear()
        r.onData(marker); assertTrue(sent.isEmpty())
    }

    @Test fun linkUpSendsStartIdentificationOnNegotiatedControlSession() {
        linkUp()
        val start = sent.mapNotNull { runCatching { message(it) }.getOrNull() }
            .firstOrNull { it.first == 0x1D00 }
        assertNotNull(start)
        val startWire = sent.first { runCatching { message(it).first }.getOrNull() == 0x1D00 }
        assertEquals(1, frame(startWire).session.toInt() and 0xFF)
    }

    @Test fun identificationAcceptedThenRequestsCertificate() {
        linkUp(); sent.clear()
        r.onData(carData(seq = 0x12, ack = 2, id = 0x1D01, params = Iap2ControlSession.param(0, "Car\u0000".encodeToByteArray())))
        val ids = sent.mapNotNull { runCatching { message(it).first }.getOrNull() }
        assertTrue(0x1D02 in ids, "identification acceptance was not queued: $ids")
        assertTrue(0xAA00 in ids, "certificate request was not queued: $ids")
        assertTrue(logs.any { it.contains("car msg 0x1D01") && it.contains("0:4") })
    }

    @Test fun certificateAndResponseUseProviderAndDoNotRequestWifi() {
        val hash = byteArrayOf(1, 2, 3, 4)
        val provider = object : MfiAuthProvider {
            override val name = "test-provider"
            override suspend fun sign(challenge: ByteArray): ByteArray = hash
            override suspend fun certificate(): ByteArray? = null
        }
        val tx = mutableListOf<ByteArray>()
        val logLines = mutableListOf<String>()
        val auth = IapOutResponder({ tx += it }, { logLines += it }, provider)
        linkUp(auth)
        auth.onData(carData(seq = 0x12, ack = 2, id = 0x1D01))
        val aa00 = tx.first { runCatching { message(it).first }.getOrNull() == 0xAA00 }
        auth.onData(carData(seq = 0x13, ack = frame(aa00).seq.toInt() and 0xFF, id = 0xAA01, params = Iap2ControlSession.param(0, byteArrayOf(9, 8, 7))))
        val challenge = tx.mapNotNull { runCatching { message(it) }.getOrNull() }
            .firstOrNull { it.first == 0xAA02 }
        assertNotNull(challenge)
        assertContentEquals(hash, challenge.second)
        val aa02 = tx.first { runCatching { message(it).first }.getOrNull() == 0xAA02 }
        auth.onData(carData(seq = 0x14, ack = frame(aa02).seq.toInt() and 0xFF, id = 0xAA03, params = Iap2ControlSession.param(0, byteArrayOf(0x55))))
        val ids = tx.mapNotNull { runCatching { message(it).first }.getOrNull() }
        assertTrue(0xAA05 in ids, "auth success was not queued: $ids")
        assertFalse(ids.contains(0x5702), "Wi-Fi request must be isolated")
        assertTrue(logLines.any { it.contains("auth outcome=accepted") })
    }

    @Test fun authenticationCompletionCallbackFiresOncePerLinkAndResetsAfterPeerReset() {
        val provider = object : MfiAuthProvider {
            override val name = "completion-callback-provider"
            override suspend fun sign(challenge: ByteArray): ByteArray = byteArrayOf(1, 2, 3)
            override suspend fun certificate(): ByteArray? = null
        }
        val tx = mutableListOf<ByteArray>()
        var completed = 0
        val auth = IapOutResponder({ tx += it }, {}, provider)
        auth.onAuthenticationComplete = { completed += 1 }

        fun authenticate(firstDataSeq: Int, expectedCompletions: Int) {
            val start = tx.last { runCatching { message(it).first }.getOrNull() == 0x1D00 }
            auth.onData(carData(seq = firstDataSeq, ack = frame(start).seq.toInt() and 0xFF, id = 0x1D01))
            val aa00 = tx.last { runCatching { message(it).first }.getOrNull() == 0xAA00 }
            auth.onData(
                carData(
                    seq = firstDataSeq + 1,
                    ack = frame(aa00).seq.toInt() and 0xFF,
                    id = 0xAA01,
                    params = Iap2ControlSession.param(0, byteArrayOf(9, 8, 7)),
                ),
            )
            val aa02 = tx.last { runCatching { message(it).first }.getOrNull() == 0xAA02 }
            val response = carData(
                seq = firstDataSeq + 2,
                ack = frame(aa02).seq.toInt() and 0xFF,
                id = 0xAA03,
                params = Iap2ControlSession.param(0, byteArrayOf(0x55)),
            )
            auth.onData(response)
            assertEquals(expectedCompletions, completed, "authentication must invoke the callback once per link")

            // A retransmitted valid response is a duplicate peer sequence and
            // must not re-run the post-auth hook.
            auth.onData(response)
            assertEquals(expectedCompletions, completed, "duplicate authentication response must be ignored")
        }

        linkUp(auth)
        authenticate(firstDataSeq = 0x12, expectedCompletions = 1)

        auth.onData(Iap2Frame(0x10, 0x44, 2, 0, ByteArray(0)).encode())
        assertEquals(IapOutResponder.State.IDLE, auth.state)

        // A peer reset starts a fresh iAP link. The callback guard must reset
        // with that link, allowing exactly one callback for the next auth.
        auth.onData(syn(seq = 0x22))
        auth.onData(Iap2Frame(0x40, 0x22, 1, 0, ByteArray(0)).encode())
        authenticate(firstDataSeq = 0x23, expectedCompletions = 2)
        assertEquals(2, completed, "a new link must get one fresh callback")
    }

    @Test fun sideEffectMessagesAreLoggedAndDropped() {
        linkUp(); sent.clear()
        r.onData(carData(seq = 0x12, ack = 2, id = 0x5703, params = Iap2ControlSession.param(1, "ssid\u0000".encodeToByteArray())))
        assertEquals(1, sent.size)
        assertEquals(0x40, frame(sent.single()).control.toInt() and 0xFF)
        assertTrue(logs.any { it.contains("ignored side effect 0x5703") })
    }

    @Test fun unackedControlRetransmitsWithBoundedTimer() {
        linkUp()
        val before = sent.size
        val now = System.nanoTime() / 1_000_000L
        r.tick(now + 2_501)
        assertTrue(sent.size > before, "expected timer retransmission")
        assertTrue(logs.any { it.contains("retransmit") })
    }

    @Test fun duplicateDataDoesNotDispatchTwice() {
        linkUp()
        val data = carData(seq = 0x12, ack = 2, id = 0x1D01)
        r.onData(data)
        val firstIds = sent.mapNotNull { runCatching { message(it).first }.getOrNull() }
        r.onData(data)
        val secondIds = sent.mapNotNull { runCatching { message(it).first }.getOrNull() }
        assertEquals(firstIds.count { it == 0x1D02 }, secondIds.count { it == 0x1D02 })
        assertEquals(firstIds.count { it == 0xAA00 }, secondIds.count { it == 0xAA00 })
        assertTrue(logs.any { it.contains("dropped duplicate") })
    }

    @Test fun duplicateCertificateDoesNotSignTwice() {
        var signCalls = 0
        val provider = object : MfiAuthProvider {
            override val name = "counting-provider"
            override suspend fun sign(challenge: ByteArray): ByteArray {
                signCalls += 1
                return byteArrayOf(1, 2, 3)
            }
            override suspend fun certificate(): ByteArray? = null
        }
        val tx = mutableListOf<ByteArray>()
        val logLines = mutableListOf<String>()
        val auth = IapOutResponder({ tx += it }, { logLines += it }, provider)
        linkUp(auth)
        auth.onData(carData(seq = 0x12, ack = 2, id = 0x1D01))
        val aa00 = tx.first { runCatching { message(it).first }.getOrNull() == 0xAA00 }
        val cert = carData(
            seq = 0x13,
            ack = frame(aa00).seq.toInt() and 0xFF,
            id = 0xAA01,
            params = Iap2ControlSession.param(0, byteArrayOf(9, 8, 7)),
        )
        auth.onData(cert)
        auth.onData(cert)
        assertEquals(1, signCalls)
        assertEquals(1, tx.mapNotNull { runCatching { message(it).first }.getOrNull() }.count { it == 0xAA02 })
        assertTrue(logLines.any { it.contains("dropped duplicate") })
    }

    @Test fun wrongSessionIsLoggedAndNeverRouted() {
        linkUp(); sent.clear()
        r.onData(carData(seq = 0x12, ack = 2, id = 0x1D01, session = 2))
        assertEquals(1, sent.size)
        assertEquals(0x40, frame(sent.single()).control.toInt() and 0xFF)
        assertTrue(logs.any { it.contains("ignored wrong session") })
        assertTrue(sent.mapNotNull { runCatching { message(it).first }.getOrNull() }.isEmpty())
    }

    @Test fun bareAckDoesNotRegressLastAcceptedPeerSequence() {
        linkUp()
        // Consume StartIdentification while advancing the peer stream to 0x12.
        r.onData(carData(seq = 0x12, ack = 2, id = 0x1D00))
        // This ACK's own seq is unrelated to the receive stream and must not
        // become the cumulative receive ACK.
        r.onData(Iap2Frame(0x40, 0x99.toByte(), 2, 0, ByteArray(0)).encode())
        sent.clear()
        // Skip 0x13. The responder must ACK 0x12 and drop the message rather
        // than routing it (the old code ACKed the out-of-order seq itself).
        r.onData(carData(seq = 0x14, ack = 2, id = 0x1D01))
        assertEquals(1, sent.size)
        assertEquals(0x12, frame(sent.single()).ack.toInt())
        assertTrue(logs.any { it.contains("dropped out-of-order") })
    }

    @Test fun retransmitExhaustionDropsQueuedLaterFrames() {
        var now = 0L
        val tx = mutableListOf<ByteArray>()
        val logLines = mutableListOf<String>()
        val limitedLsp = lsp.copyOf().also { it[1] = 1 }
        val auth = IapOutResponder({ tx += it }, { logLines += it }, clockMs = { now })
        auth.onData(syn(payload = limitedLsp))
        auth.onData(Iap2Frame(0x40, 0x11, 1, 0, ByteArray(0)).encode())
        auth.onData(carData(seq = 0x12, ack = 2, id = 0x1D01))
        assertTrue(tx.mapNotNull { runCatching { message(it).first }.getOrNull() }.contains(0x1D02))
        assertFalse(tx.mapNotNull { runCatching { message(it).first }.getOrNull() }.contains(0xAA00))

        now = 2_000L; auth.tick(now)
        now = 4_000L; auth.tick(now)
        now = 6_000L; auth.tick(now)
        now = 8_000L; auth.tick(now)
        val sendsAtFailure = tx.size
        assertEquals(IapOutResponder.State.IDLE, auth.state)
        assertTrue(logLines.any { it.contains("retransmit exhaustion") })
        assertFalse(tx.mapNotNull { runCatching { message(it).first }.getOrNull() }.contains(0xAA00))
        now = 12_000L; auth.tick(now)
        assertEquals(sendsAtFailure, tx.size)
    }

    @Test fun resetClearsSequenceAndSessionState() {
        linkUp()
        r.onData(Iap2Frame(0x10, 0x44, 2, 0, ByteArray(0)).encode())
        assertEquals(IapOutResponder.State.IDLE, r.state)
        sent.clear()
        r.onData(syn(seq = 0x22))
        val synAck = frame(sent.single())
        assertEquals(1, synAck.seq.toInt())
        assertEquals(0x22, synAck.ack.toInt())
    }
    @Test fun nowPlayingPublishesLiveStateOnlyWhileSubscribed() {
        val wire = mutableListOf<ByteArray>()
        var snapshot = com.cardroid.lib.NowPlayingState(title = "Track", status = 1)
        val responder = IapOutResponder({ wire += it }, {}, nowPlaying = { snapshot })
        linkUp(responder)
        fun updates() = wire.mapNotNull { Iap2ControlSession.parse(frame(it).payload) }.count { it.first == 0x5001 }
        responder.tick()
        assertEquals(0, updates())
        responder.onData(carData(0x12, 2, 0x5000))
        assertEquals(1, updates())
        responder.tick()
        assertEquals(1, updates()) // unchanged snapshots do not flood the link
        snapshot = snapshot.copy(status = 2, positionMs = 3603)
        responder.tick()
        assertEquals(2, updates())
        responder.onData(carData(0x13, 4, 0x5002))
        snapshot = snapshot.copy(title = "Next track")
        responder.tick()
        assertEquals(2, updates())
    }
}
