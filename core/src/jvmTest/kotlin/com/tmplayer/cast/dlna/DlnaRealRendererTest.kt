package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastResource
import com.tmplayer.cast.CastServer
import com.tmplayer.cast.FileCastSource
import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.LanAddress
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real-renderer check: TMPlayer's own DLNA client (discovery, description, SOAP, DIDL, the cast
 * server, polling) against software renderers running on this machine, with real multicast and
 * real time. `scripts/dlna/e2e.sh` starts the renderers, makes the test videos and runs this;
 * on its own it is skipped unless these are set:
 *
 *  - `DLNA_E2E_IFACE`: this machine's LAN IPv4 address (where the renderers listen too).
 *  - `DLNA_E2E_NAMES`: the renderers' friendly names, comma separated.
 *  - `DLNA_E2E_MP4`, `DLNA_E2E_MKV`: an H.264 MP4 and MKV of 120 s.
 *  - `DLNA_E2E_OUT`: the folder for the record, one folder per renderer: `steps.txt` (pass, fail
 *    or skip per step), `soap.log` (every SOAP request and answer, headers and bodies) and
 *    `http.log` (every request the renderer made to the cast server).
 *
 * A step that fails does not stop the others; the test fails at the end if any did. WARN is a
 * renderer doing something no client can see through (said what), not a failure of TMPlayer's.
 */
class DlnaRealRendererTest {

    private class Steps(val renderer: String) {
        val lines = ArrayList<String>()
        var failed = 0

        fun pass(step: String, detail: String = "") = add("PASS", step, detail)
        fun skip(step: String, detail: String) = add("SKIP", step, detail)
        fun warn(step: String, detail: String) = add("WARN", step, detail)
        fun fail(step: String, detail: String) {
            failed++
            add("FAIL", step, detail)
        }

        fun check(step: String, ok: Boolean, detail: String) = if (ok) pass(step, detail) else fail(step, detail)

        private fun add(result: String, step: String, detail: String) {
            val line = "%-4s %-28s %s".format(result, step, detail)
            lines += line
            println("[$renderer] $line")
        }
    }

    @Test
    fun everyStepAgainstEachRenderer() {
        val iface = System.getenv("DLNA_E2E_IFACE")
        val names = System.getenv("DLNA_E2E_NAMES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        assumeTrue("set DLNA_E2E_IFACE and DLNA_E2E_NAMES (scripts/dlna/e2e.sh does)", iface != null && names.isNotEmpty())
        val me = InetAddress.getByName(iface)
        val out = File(System.getenv("DLNA_E2E_OUT") ?: "build/dlna-e2e").apply { mkdirs() }
        val mp4 = System.getenv("DLNA_E2E_MP4")?.let(::File)?.takeIf { it.isFile }
        val mkv = System.getenv("DLNA_E2E_MKV")?.let(::File)?.takeIf { it.isFile }

        val found = discover(me, names)
        val summary = ArrayList<String>()
        var failures = 0
        for (name in names) {
            val dir = File(out, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
            val steps = Steps(name)
            try {
                runOne(name, found[name], me, mp4, mkv, dir, steps)
            } catch (e: Throwable) {
                steps.fail("harness", "${e::class.simpleName}: ${e.message}")
            }
            File(dir, "steps.txt").writeText(steps.lines.joinToString("\n", postfix = "\n"))
            summary += "== $name (${steps.failed} failed)"
            summary += steps.lines
            failures += steps.failed
        }
        File(out, "summary.txt").writeText(summary.joinToString("\n", postfix = "\n"))
        println(summary.joinToString("\n"))
        if (failures > 0) throw AssertionError("$failures step(s) failed; see ${out.absolutePath}/summary.txt")
    }

    /** One real search on [me]'s interface until every name in [names] has answered, or 20 s. */
    private fun discover(me: InetAddress, names: List<String>): Map<String, Pair<DlnaDevice, Long>> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Pair<DlnaDevice, Long>>()
        val started = System.nanoTime()
        val discovery = DlnaDiscovery(
            object : DlnaDiscovery.Listener {
                override fun onFound(device: DlnaDevice) {
                    names.firstOrNull { device.name.equals(it, ignoreCase = true) }?.let { found.putIfAbsent(it, device to (System.nanoTime() - started) / 1_000_000) }
                }
                override fun onGone(udn: String) = Unit
                override fun onFinished(found: Int) = Unit
            },
            interfaces = { LanAddress.candidates().filter { it.address == me } },
            live = true,
        )
        discovery.start()
        val until = System.nanoTime() + 20_000_000_000L
        while (found.size < names.size && System.nanoTime() < until) Thread.sleep(200)
        discovery.stop()
        return found
    }

    private fun runOne(name: String, hit: Pair<DlnaDevice, Long>?, me: InetAddress, mp4: File?, mkv: File?, dir: File, steps: Steps) {
        if (hit == null) {
            steps.fail("discovery", "no answer from '$name' in 20 s")
            return
        }
        val (device, ms) = hit
        steps.pass("discovery", "found in $ms ms at ${device.address.hostAddress}, ${device.location}")

        val d = device.description
        File(dir, "description.xml").writeText(DlnaDiscovery.fetchDescription(device.location, 3_000).orEmpty())
        steps.check(
            "description", true,
            "${d.manufacturer} ${d.modelName}; AVTransport ${d.avTransport.type}; " +
                "RenderingControl ${d.renderingControl != null}; ConnectionManager ${d.connectionManager != null}",
        )

        val soapLog = File(dir, "soap.log").apply { writeText("") }
        val httpLog = File(dir, "http.log").apply { writeText("") }
        val calls = AtomicInteger()
        val soap = SoapClient(trace = { url, request, status, response ->
            synchronized(soapLog) {
                soapLog.appendText("##### ${calls.incrementAndGet()} ${System.currentTimeMillis()} -> $url (status $status)\n$request\n----- answer\n$response\n\n")
            }
        })
        val fetches = AtomicInteger()
        val onRequest: (InetAddress, String) -> Unit = { from, line ->
            fetches.incrementAndGet()
            synchronized(httpLog) { httpLog.appendText("${System.currentTimeMillis()} ${from.hostAddress} $line\n") }
        }

        val probe = DlnaRenderer(device, soap)
        val sink = probe.sinkCaps()
        steps.check("GetProtocolInfo", d.connectionManager == null || sink.declared, "declared=${sink.declared} containers=${sink.containers} takesVideo=${sink.takesVideo}")
        steps.check("first poll", probe.pollOnce() != null, "state ${probe.media?.state}")

        if (mp4 == null) {
            steps.skip("mp4", "DLNA_E2E_MP4 not set")
        } else {
            castFile(device, me, soap, onRequest, fetches, mp4, "video/mp4", "mp4", steps, full = true)
        }
        if (mkv == null) {
            steps.skip("mkv", "DLNA_E2E_MKV not set")
        } else {
            castFile(device, me, soap, onRequest, fetches, mkv, "video/x-matroska", "mkv", steps, full = false)
        }
        failureSteps(device, me, soap, onRequest, steps)
    }

    /** Loads [file] and drives it; [full] runs every control step, else load, play and stop. */
    private fun castFile(
        device: DlnaDevice, me: InetAddress, soap: SoapTransport, onRequest: (InetAddress, String) -> Unit, fetches: AtomicInteger,
        file: File, mime: String, tag: String, steps: Steps, full: Boolean,
    ) {
        val medias = LinkedBlockingQueue<ReceiverMedia>()
        val closed = LinkedBlockingQueue<DlnaClosed>()
        val cast = DlnaCast(device, me, soap = soap, onClosed = { closed += it }, onRequest = onRequest)
        try {
            val before = fetches.get()
            // Apostrophes, an ampersand and other scripts in the title: a real DIDL parser reads it.
            val video = DlnaVideo("TMPlayer check: Tom's & Jerry's Привет 日本 ($tag)", CastResource.Ranged(FileCastSource(file), mime), mime, durationMs = 120_000, sizeBytes = file.length())
            val load = cast.open(video, 0) { medias += it }
            steps.check("$tag SetAVTransportURI+Play", load == DlnaLoad.Loaded, "$load, address ${cast.server.urlFor("video.$tag")}")
            if (load != DlnaLoad.Loaded) return
            val playing = waitFor(medias, 15_000) { it.state == ReceiverState.Playing }
            steps.check("$tag fetched", fetches.get() > before, "${fetches.get() - before} request(s) to the cast server")
            steps.check("$tag playing", playing != null, playing?.toString() ?: "never PLAYING in 15 s (last ${cast.renderer.media}, failure ${cast.failure})")
            if (playing == null) return
            Thread.sleep(3_000)
            val later = latest(medias)?.takeIf { it.state == ReceiverState.Playing } ?: waitFor(medias, 3_000) { it.state == ReceiverState.Playing }
            val moved = (later?.positionMs ?: 0) - playing.positionMs
            val fast = moved > 3 * 3_000
            steps.check("$tag position advances", later != null && moved >= 1_500, "${playing.positionMs} -> ${later?.positionMs} in about 3 s")
            if (fast) steps.warn("$tag real time", "moved $moved ms in about 3 s: this renderer plays faster than real time (no audio clock), so its end comes early")
            if (!full) {
                cast.stop()
                steps.check("$tag stop", closed.poll(5, TimeUnit.SECONDS) == DlnaClosed.Stopped && !cast.server.running, "closed")
                return
            }

            steps.check("pause sent", cast.pause(), "Pause")
            val paused = waitFor(medias, 7_000) { it.state == ReceiverState.Paused }
            steps.check("paused", paused != null, paused?.toString() ?: "never PAUSED (last ${cast.renderer.media})")
            val resume = cast.play()
            val again = waitFor(medias, 7_000) { it.state == ReceiverState.Playing }
            steps.check("play again", resume == DlnaLoad.Loaded && again != null, "$resume ${again ?: cast.renderer.media}")

            if (!cast.canSeek) {
                steps.skip("seek", "the renderer's actions leave Seek out: ${cast.renderer.actions}")
            } else {
                val seek = cast.seek(15_000)
                val there = waitFor(medias, 10_000) { it.state == ReceiverState.Playing && it.positionMs in 13_000..25_000 }
                steps.check("seek to 0:15", seek == SeekOutcome.Done && there != null, "$seek, then ${there ?: cast.renderer.media}")
            }

            // The TV's own remote: another control point changes the state, and the polls follow.
            val remote = SoapClient()
            fun tv(action: String, vararg args: Pair<String, String>) = remote.call(device.description.avTransport, action, listOf("InstanceID" to "0") + args)
            tv("Pause")
            val tvPaused = waitFor(medias, 7_000) { it.state == ReceiverState.Paused }
            steps.check("TV pauses by itself", tvPaused != null, tvPaused?.toString() ?: "poll never saw it (last ${cast.renderer.media})")
            tv("Play", "Speed" to "1")
            val tvPlays = waitFor(medias, 7_000) { it.state == ReceiverState.Playing }
            steps.check("TV plays by itself", tvPlays != null, tvPlays?.toString() ?: "poll never saw it (last ${cast.renderer.media})")
            tv("Stop")
            val tvStopped = waitFor(medias, 7_000) { it.state == ReceiverState.Idle }
            val stopDetail = "${tvStopped ?: cast.renderer.media} (Interrupted is right: not Cancelled, not Finished)"
            when {
                tvStopped?.idleReason == IdleReason.Interrupted -> steps.pass("TV stops by itself", stopDetail)
                // A renderer that raced to the end before the Stop: Finished is then the truth.
                tvStopped?.idleReason == IdleReason.Finished && tvStopped.positionMs >= 118_000 -> steps.warn("TV stops by itself", "$stopDetail; it had reached the end first")
                else -> steps.fail("TV stops by itself", stopDetail)
            }
            cast.stop()
            steps.check("stop", closed.poll(5, TimeUnit.SECONDS) == DlnaClosed.Stopped && !cast.server.running, "DlnaClosed.Stopped, server closed")
        } finally {
            cast.close()
        }
    }

    /** The two ways a load can go wrong after SetAVTransportURI, and the step each must name. */
    private fun failureSteps(device: DlnaDevice, me: InetAddress, soap: SoapTransport, onRequest: (InetAddress, String) -> Unit, steps: Steps) {
        // Nothing listens at the address: the TV can never fetch it.
        val port = ServerSocket(0, 1, me).use { it.localPort }
        val dead = "http://${me.hostAddress}:$port/nothing/video.mp4"
        val r1 = DlnaRenderer(device, soap, watchFetches = true)
        val l1 = r1.load(dead, Didl.item("TMPlayer check: unreachable", dead, "video/mp4", DlnaFeatures()))
        val f1 = settle(r1, l1)
        steps.check(
            "fails: nothing to fetch", f1 == DlnaFailure.NeverFetched || f1 == DlnaFailure.CouldNotOpen || f1 == DlnaFailure.NoAnswer,
            "load $l1, failure $f1, says: ${f1?.text()}",
        )
        runCatching { r1.stop() }
        r1.close()

        // The server answers, but 404: the TV came for the video and stopped.
        val r2 = DlnaRenderer(device, soap, watchFetches = true)
        val server = CastServer(
            me, allowed = { it == device.address },
            onRequest = { from, line ->
                if (from == device.address) r2.noteFetched()
                onRequest(from, line)
            },
        )
        server.start()
        try {
            val missing = server.urlFor("missing.mp4")
            val l2 = r2.load(missing, Didl.item("TMPlayer check: missing", missing, "video/mp4", DlnaFeatures()))
            val f2 = settle(r2, l2)
            val detail = "load $l2, failure $f2, says: ${f2?.text()}"
            when {
                f2 == DlnaFailure.StoppedBeforePlaying || f2 == DlnaFailure.CouldNotOpen -> steps.pass("fails: 404 from the server", detail)
                // It came, got a 404, and says PLAYING at 0:00 with status OK for good: nothing a client can tell apart from a slow start.
                f2 == null && r2.media?.state == ReceiverState.Playing -> steps.warn("fails: 404 from the server", "$detail; the renderer reports PLAYING after a 404")
                else -> steps.fail("fails: 404 from the server", detail)
            }
        } finally {
            runCatching { r2.stop() }
            r2.close()
            server.stop()
        }
    }

    /** The failure [load] came to: its own refusal, or what 25 s of polls made of it. */
    private fun settle(r: DlnaRenderer, load: DlnaLoad): DlnaFailure? {
        DlnaFailure.of(load)?.let { return it }
        val until = System.nanoTime() + 25_000_000_000L
        while (System.nanoTime() < until) {
            r.pollOnce()
            r.failure?.let { return it }
            Thread.sleep(1_000)
        }
        return null
    }

    /** The newest status in [q], the older ones dropped. */
    private fun latest(q: LinkedBlockingQueue<ReceiverMedia>): ReceiverMedia? {
        var last: ReceiverMedia? = null
        while (true) last = q.poll() ?: return last
    }

    private fun waitFor(q: LinkedBlockingQueue<ReceiverMedia>, ms: Long, ok: (ReceiverMedia) -> Boolean): ReceiverMedia? {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            val m = q.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (ok(m)) return m
        }
        return null
    }
}
