package com.aonminers.smminer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

// ── Configurable constants (top of file as requested) ─────────

/** ASIC frequency in MHz. */
var AON_FREQUENCY: Int = 150

/** Job send interval in milliseconds. */
var AON_JOB_TIMER: Int = 20

/** Baud rate: 1 = 1M, 2 = 1.5M. */
var AON_BAUDRATE: Int = 1

// BM1368 chip protocol constants
private val BM_PREAMBLE = byteArrayOf(0x55.toByte(), 0xAA.toByte())
private const val BM_TYPE_JOB = 0x20
private const val BM_GROUP_SINGLE = 0x00
private const val BM_CMD_WRITE = 0x01

// ── Data structures ──────────────────────────────────────────

/** Mirrors Go's BMJob. */
data class BMJob(
    val midstateID: Byte = 0,
    var jobID: Byte = 0,
    val midstatesCount: Byte = 1,
    val startingNonce: ByteArray = byteArrayOf(0, 0, 0, 4),
    val networkTarget: ByteArray = ByteArray(4),
    val blockTimestamp: ByteArray = ByteArray(4),
    val merkleRoot: ByteArray = ByteArray(32),
    val prevBlockHash: ByteArray = ByteArray(32),
    val version: ByteArray = ByteArray(4),
    var stratumJob: StratumJob? = null
)

/** Mirrors Go's StratumWork in drivers package. */
data class AonWork(
    val id: String,
    val workID: String,
    val hash: String = "",
    val nonce: String,
    val difficulty: Double,
    val version: String,
    val extraNonce2: String,
    val blockTimestamp: String
)

// ── Global state (matches Go globals) ────────────────────────

private val finishedWorks = mutableListOf<AonWork>()
private val pendingJobs = mutableListOf<MutableList<BMJob>>() // [devID] -> jobs
private val stratumJobs = mutableListOf<StratumJob>()         // [devID] -> current stratum job
private val stratumJobBuffer = mutableListOf<StratumJob>()    // [devID] -> device-specific job (matches Go)

private var workTotal = mutableListOf<Long>()      // [devID]
private var workTimers = mutableListOf<Long>()      // [devID] (ms)
private var lastReport = 0L
private var lastShareTime = 0L
private var lastJobID: Byte = 0

@Volatile var hashrate = 0.0
@Volatile var bestShare = 0.0
private var appContext: Context? = null  // stored for service stop
@Volatile private var needsRestart = false
private var restartCfg: RestartConfig? = null

private data class RestartConfig(
    val ports: List<UsbPort>,
    val client: StratumClient,
    val ctx: Context,
    val debug: Boolean,
    val lang: Language,
    val logCallback: (String) -> Unit,
    val poolUrl: String,
    val username: String,
    val password: String,
    val suggestDifficulty: Double
)
private val writeErrors = AtomicInteger(0)
private val hwErrors = AtomicInteger(0)

private val running = AtomicBoolean(false)
private val threads = mutableListOf<Thread>()
private var devices = listOf<UsbPort>()
private var onLog: (String) -> Unit = {}
private var debugMode = false
private var lang: Language = Language.EN

private fun t(en: String, pt: String, vararg args: Any?): String {
    val tpl = if (lang == Language.PT) pt else en
    return if (args.isEmpty()) tpl else String.format(tpl, *args)
}

// Debug-wrapped USB I/O
private fun usbWrite(port: UsbPort, data: ByteArray): Int {
    if (debugMode) onLog("TX ${port.info.port}: ${bytesToHex(data)}")
    return port.write(data)
}

private fun usbRead(port: UsbPort, maxLen: Int, timeoutMs: Int): ByteArray {
    val buf = port.read(maxLen, timeoutMs)
    if (debugMode && buf.isNotEmpty()) onLog("RX ${port.info.port}: ${bytesToHex(buf)}")
    return buf
}

// ── Public API ────────────────────────────────────────────────

fun startAonMiner(
    ports: List<UsbPort>,
    stratumClient: StratumClient,
    context: Context,
    debug: Boolean = false,
    language: Language = Language.EN,
    log: (String) -> Unit,
    poolUrl: String = "",
    username: String = "",
    password: String = "",
    suggestDifficulty: Double = 254.0
) {
    if (running.getAndSet(true)) return
    threads.clear()
    onLog = log
    debugMode = debug
    lang = language
    devices = ports
    appContext = context.applicationContext
    restartCfg = RestartConfig(ports, stratumClient, context, debug, language, log, poolUrl, username, password, suggestDifficulty)

    finishedWorks.clear()
    pendingJobs.clear()
    stratumJobs.clear()
    stratumJobBuffer.clear()
    workTotal.clear()
    workTimers.clear()
    bestShare = 0.0
    hashrate = 0.0
    lastJobID = 0
    writeErrors.set(0)
    hwErrors.set(0)

    for (i in devices.indices) {
        pendingJobs.add(mutableListOf())
        stratumJobs.add(StratumJob())
        stratumJobBuffer.add(StratumJob())
        workTotal.add(0)
        workTimers.add(System.currentTimeMillis())
    }
    lastReport = System.currentTimeMillis()
    lastShareTime = System.currentTimeMillis()

    // Initialize each device
    var initialized = AtomicInteger(0)
    for ((i, port) in devices.withIndex()) {
        threads.add(thread(name = "aon-init-$i") {
            try {
                asicInit(port, i)
            } catch (e: Exception) {
                onLog("> [port ${port.info.port}] ${t("init failed:","falha na inicialização:")} ${e.message}")
            }
            initialized.incrementAndGet()
        })
        Thread.sleep(200)
    }
    while (initialized.get() < devices.size && running.get()) {
        Thread.sleep(500)
    }
    if (!running.get()) return

    onLog("> ${devices.size} ${t("device(s) initialized, starting read loops...","dispositivo(s) inicializado(s), iniciando loops de leitura...")}")

    // Start foreground notification service
    val serviceIntent = Intent(context, MiningService::class.java)
    ContextCompat.startForegroundService(context, serviceIntent)

    // Start read loops
    for ((i, port) in devices.withIndex()) {
        threads.add(thread(name = "aon-read-$i") {
            try { asicReadLoop(i, port, stratumClient) }
            catch (e: Exception) { onLog("> ${t("read loop","loop de leitura")} $i ${t("error:","erro:")} ${e.message}") }
        })
    }

    // Start job sender loop
    threads.add(thread(name = "aon-sender") {
        try { jobSenderLoop(stratumClient) }
        catch (e: Exception) { onLog("> ${t("job sender error:","erro no job sender:")} ${e.message}") }
    })

    // Start submit loop
    threads.add(thread(name = "aon-submit") {
        try { submitLoop(stratumClient) }
        catch (e: Exception) { onLog("> ${t("submit loop error:","erro no submit loop:")} ${e.message}") }
    })

    // Fast hashrate poller — feeds the foreground notification every ~8 s
    threads.add(thread(name = "aon-hashrate") {
        val factor = 4_294_967_296.0 // 2^32
        while (running.get()) {
            try { Thread.sleep(8000) } catch (_: InterruptedException) { break }
            if (!running.get()) break
            var totalGh = 0.0
            val now = System.currentTimeMillis()
            synchronized(workTotal) {
                for (i in devices.indices) {
                    val elapsed = ((now - workTimers[i]) / 1000.0).coerceAtLeast(1.0)
                    val wt = if (i < workTotal.size) workTotal[i] else 0
                    totalGh += wt.toDouble() * factor / elapsed / 1e9
                }
            }
            hashrate = totalGh * 1e9 // store as H/s for the notification
        }
    })
}

fun stopAonMiner() {
    running.set(false)
    // Stop foreground notification service immediately
    appContext?.let { ctx ->
        try { ctx.stopService(Intent(ctx, MiningService::class.java)) } catch (_: Exception) {}
    }
    // Interrupt all background threads first so they stop blocking on I/O
    val current = Thread.currentThread()
    for (t in threads) {
        if (t == current) continue
        try { t.interrupt() } catch (_: Exception) {}
    }
    // Then join with a reasonable timeout
    for (t in threads) {
        if (t == current) continue  // skip self to avoid deadlock
        try { t.join(5000) } catch (_: Exception) {}
    }
    threads.clear()
    resetState()
}

/** Reset in-memory state + close USB ports (call only when all threads are stopped). */
private fun resetState() {
    stratumJobBuffer.clear()
    finishedWorks.clear()
    pendingJobs.clear()
    stratumJobs.clear()
    workTotal.clear()
    workTimers.clear()
    // Small delay to let any in-flight bulkTransfer finish before closing ports
    Thread.sleep(200)
    for (d in devices) {
        try { d.close() } catch (_: Exception) {}
    }
    // Force-release any stale USB interface claims so a fresh scan succeeds
    appContext?.let { UsbPort.releaseStale(it) }
    bestShare = 0.0
    hashrate = 0.0
    lastJobID = 0
    writeErrors.set(0)
    hwErrors.set(0)
}

fun isAonMinerRunning(): Boolean = running.get()

// ── ASIC Init ─────────────────────────────────────────────────

private fun asicInit(port: UsbPort, devID: Int) {
    val freqTarget = (AON_FREQUENCY / 6.25).toInt().coerceIn(24, 128)
    val freqMhz = String.format("%.2f", 6.25 * freqTarget)
    onLog("> [port ${port.info.port}] ${t("init, freq=","init, freq=")}${freqMhz}MHz, baud=$AON_BAUDRATE")

    // ── Set initial baud rate (must be done BEFORE sending commands) ──
    // The Go AsicScan calls SerialFTDISetConfig BEFORE AsicInit.
    if (!port.setConfig(115_200, 8, 1, 0)) {
        onLog("> [port ${port.info.port}] ${t("setConfig(115200) failed","setConfig(115200) falhou")}")
    }
    // FTDI default latency is 16ms — must be << read-loop timeout so
    // the chip forwards UART data in time instead of buffering it.
    //if (!port.setLatency(2)) {
    //    onLog("> [port ${port.info.port}] setLatency(2ms) failed")
    //}

    // ── RTS toggle pulse to reset the BM1368 chip ──
    // Matches Go AsicInit: LOW→HIGH 500ms HIGH→LOW 500ms
    port.toggleRTS() // LOW → HIGH
    Thread.sleep(500)
    port.toggleRTS() // HIGH → LOW
    Thread.sleep(500)

    // Build init sequence (matches Go initCommands exactly)
    val initCmds = mutableListOf<ByteArray>()

    // Reset / wake-up commands
    repeat(2) { initCmds.add(hexToBytes("55AA52050000")) }
    repeat(3) { initCmds.add(hexToBytes("55AA53050000")) }
    initCmds.add(hexToBytes("55AA40050000"))

    // Core register configs
    initCmds.add(hexToBytes("55AA510900A49000FFFF"))
    repeat(3) { initCmds.add(hexToBytes("55AA510900A49000FFFF")) }
    initCmds.add(hexToBytes("55AA510900A800070000"))
    initCmds.add(hexToBytes("55AA51090018FF0FC100"))
    initCmds.add(hexToBytes("55AA5109003C80008B00"))
    initCmds.add(hexToBytes("55AA5109003C80008018"))

    // Ticket mask 255
    initCmds.add(hexToBytes("55AA51090014000000FF"))
    // Drive strength + analog mux
    initCmds.add(hexToBytes("55AA5109005802111111"))
    initCmds.add(hexToBytes("55AA410900A800070000"))
    initCmds.add(hexToBytes("55AA41090018F000C100"))
    initCmds.add(hexToBytes("55AA4109003C80008B00"))
    initCmds.add(hexToBytes("55AA4109003C80008018"))
    initCmds.add(hexToBytes("55AA4109003C800082AA"))
    initCmds.add(hexToBytes("55AA5109005400000003"))

    // PLL frequency commands
    for (freq in 10..freqTarget) {
        initCmds.add(calculatePLL(6.25 * freq, 96, 198))
    }
    // Final PLL
    initCmds.add(hexToBytes("55AA51090010000015A4"))
    initCmds.add(hexToBytes("55AA510900A49000FFFF")) // from BM1366 ESPMULTI

    // Baud rate
    when (AON_BAUDRATE) {
        1 -> initCmds.add(hexToBytes("55AA5109002811300200")) // 1M
        2 -> initCmds.add(hexToBytes("55AA5109002811300100")) // 1.5M
    }

    // Execute init sequence
    var chipDetected = false
    for (cmd in initCmds) {
        val cmdWithCrc = cmd + crc5Bm(cmd.copyOfRange(2, cmd.size))
        usbWrite(port, cmdWithCrc)

        val resp = usbRead(port, 64, 100)
        if (resp.size > 4 && !chipDetected) {
            // After stripping FTDI status bytes, CHIP_ID is at resp[2..3]
            if (resp[2] == 0x13.toByte() && resp[3] == 0x68.toByte()) {
                onLog("> [port ${port.info.port}] ${t("BM1368 chip detected","chip BM1368 detectado")}")
                chipDetected = true
            }
        }
        Thread.sleep(300)
    }
    if (!chipDetected) {
        onLog("> [port ${port.info.port}] ${t("BM1368 chip NOT detected — check USB connection and power","chip BM1368 NÃO detectado — verifique cabo USB e alimentação")}")
    }

    // Switch to high baud rate
    val newBaud = try {
        when (AON_BAUDRATE) {
            1 -> { port.setConfig(1_000_000, 8, 1, 0); 1_000_000 }
            2 -> { port.setConfig(1_500_000, 8, 1, 0); 1_500_000 }
            else -> { port.setConfig(115_200, 8, 1, 0); 115_200 }
        }
    } catch (e: Exception) {
        onLog("> [port ${port.info.port}] ${t("setConfig failed:","setConfig falhou:")} ${e.message}, ${t("keeping 115200","mantendo 115200")}")
        115200
    }
    onLog("> [port ${port.info.port}] ${t("baudrate set to","baudrate definido para")} ${newBaud / 1000}K")
    // Re-apply latency timer after baud change (some FTDI chips reset it).
    //if (!port.setLatency(2)) {
    //    onLog("> [port ${port.info.port}] setLatency(2ms) after baud change failed")
    //}

    Thread.sleep(2000)
    if (!running.get()) return
    onLog("> [port ${port.info.port}] ${t("init complete","inicialização completa")}")
}

// ── Job Construction ──────────────────────────────────────────

private fun bmConstructJob(stratumJob: StratumJob): BMJob {
    lastJobID = ((lastJobID.toInt() + 24) % 128).toByte()

    val merkleRootReversed = reverseWord32(stratumJob.merkleRoot)
    return BMJob(
        midstateID = 0,
        jobID = lastJobID,
        midstatesCount = 1,
        startingNonce = byteArrayOf(0, 0, 0, 4),
        networkTarget = reverseBytes(stringToHex(stratumJob.networkTarget)),
        blockTimestamp = reverseBytes(stringToHex(stratumJob.blockTimestamp)),
        merkleRoot = reverseBytes(stringToHex(merkleRootReversed)),
        prevBlockHash = reverseBytes(stringToHex(stratumJob.prevBlockHash)),
        version = reverseBytes(stringToHex(stratumJob.blockVersion)),
        stratumJob = stratumJob
    )
}

// ── Job Sender Loop ───────────────────────────────────────────

/** Matches Go's AonMiner main loop: ExtraNonce2 rolling + MerkleRoot per device. */
private fun jobSenderLoop(stratumClient: StratumClient) {
    var lastJobChange = 0L
    var forceUpdate = true
    var lastBlockHash = ""

    while (running.get()) {
        // ── Autonomous restart after "too many write errors" ──
        if (needsRestart) {
            needsRestart = false
            val cfg = restartCfg ?: break
            onLog("> ${t("restarting miner (full init + loop restart)...","reiniciando minerador (reinicialização completa)...")}")
            // Full stop: ASIC + old stratum
            stopAonMiner()
            cfg.client.shutdown()
            try { cfg.client.join(5000) } catch (_: Exception) {}
            onLog("> ${t("waiting 8s before restart...","aguardando 8s antes de reiniciar...")}")
            Thread.sleep(8000)
            // Start fresh StratumClient
            onLog("> ${t("connecting to","conectando a")} ${cfg.poolUrl}...")
            val newClient = StratumClient(cfg.poolUrl, cfg.username, cfg.password, cfg.suggestDifficulty, cfg.debug, cfg.lang, cfg.logCallback)
            newClient.start()
            var waited = 0
            while (!newClient.isConnected && newClient.isAlive && waited < 30_000) {
                Thread.sleep(200); waited += 200
            }
            if (!newClient.isConnected) {
                onLog("> ${t("failed to connect after","falha ao conectar após")} 30s ${t("— mining stopped","— mineração parada")}")
                newClient.shutdown()
                try { newClient.join(2000) } catch (_: Exception) {}
                return
            }
            onLog("> ${t("connected.","conectado.")}")
            // Re-scan USB
            onLog("> ${t("re-scanning USB devices...","re-escaneando dispositivos USB...")}")
            val freshPorts = UsbPort.scan(cfg.ctx, "ZX1") { cfg.logCallback(it) }
            if (freshPorts.isEmpty()) {
                onLog("> ${t("no USB devices found after restart — mining stopped","nenhum dispositivo USB encontrado após reinício — mineração parada")}")
                newClient.shutdown()
                try { newClient.join(2000) } catch (_: Exception) {}
                return
            }
            startAonMiner(freshPorts, newClient, cfg.ctx, cfg.debug, cfg.lang, cfg.logCallback, cfg.poolUrl, cfg.username, cfg.password, cfg.suggestDifficulty)
            return
        }

        // ── Pool disconnection → full stop + restart ──
        if (!stratumClient.isConnected) {
            val cfg = restartCfg ?: break
            onLog("> ${t("pool disconnected, restarting...","pool desconectada, reiniciando...")}")
            // Full stop: ASIC + old stratum
            stopAonMiner()
            cfg.client.shutdown()
            try { cfg.client.join(5000) } catch (_: Exception) {}
            Thread.sleep(3000)
            // Start fresh StratumClient
            onLog("> ${t("connecting to","conectando a")} ${cfg.poolUrl}...")
            val newClient = StratumClient(cfg.poolUrl, cfg.username, cfg.password, cfg.suggestDifficulty, cfg.debug, cfg.lang, cfg.logCallback)
            newClient.start()
            var waited = 0
            while (!newClient.isConnected && newClient.isAlive && waited < 30_000) {
                Thread.sleep(200); waited += 200
            }
            if (!newClient.isConnected) {
                onLog("> ${t("failed to connect after","falha ao conectar após")} 30s ${t("— retrying...","— tentando novamente...")}")
                newClient.shutdown()
                try { newClient.join(2000) } catch (_: Exception) {}
                Thread.sleep(5000)
                continue
            }
            onLog("> ${t("connected.","conectado.")}")
            // Re-scan USB
            onLog("> ${t("re-scanning USB devices...","re-escaneando dispositivos USB...")}")
            val freshPorts = UsbPort.scan(cfg.ctx, "ZX1") { cfg.logCallback(it) }
            if (freshPorts.isEmpty()) {
                onLog("> ${t("no USB devices found — mining stopped","nenhum dispositivo USB encontrado — mineração parada")}")
                newClient.shutdown()
                try { newClient.join(2000) } catch (_: Exception) {}
                return
            }
            startAonMiner(freshPorts, newClient, cfg.ctx, cfg.debug, cfg.lang, cfg.logCallback, cfg.poolUrl, cfg.username, cfg.password, cfg.suggestDifficulty)
            return
        }

        // ── Read current job under lock (matches Go's StratumMutex) ──
        val job: StratumJob?
        synchronized(stratumClient) {
            job = stratumClient.currentJob?.copy()   // snapshot, guards against concurrent notify
        }
        if (job == null || job.prevBlockHash.isEmpty()) {
            Thread.sleep(100)
            continue
        }
        val extraNonce2Len = stratumClient.extraNonce2Len  // pool-provided, not hardcoded

        if (job.forgetSubmits) forceUpdate = true

        val now = System.currentTimeMillis()
        // 30-second unconditional buffer refresh (prevents duplicate job sequences)
        val timerExpired = (now - lastJobChange) >= 90_000 && lastBlockHash.isNotEmpty()

        // ── Refresh buffer on: new block / 90s timeout / forgetSubmits ──
        if (job.prevBlockHash != lastBlockHash || timerExpired || forceUpdate) {
            lastBlockHash = job.prevBlockHash
            forceUpdate = false

            val baseExtraNonce2 = job.extraNonce2.toLongOrNull(16) ?: 0L
            synchronized(stratumJobBuffer) {
                for (i in devices.indices) {
                    val uniqueExtraNonce2 = baseExtraNonce2 + i + 1
                    stratumJobBuffer[i] = StratumJob(
                        id = job.id,
                        prevBlockHash = job.prevBlockHash,
                        coinbasePrefix = job.coinbasePrefix,
                        extraNonce1 = job.extraNonce1,
                        coinbaseSuffix = job.coinbaseSuffix,
                        merkleBranches = job.merkleBranches,
                        blockVersion = job.blockVersion,
                        networkTarget = job.networkTarget,
                        poolDifficulty = job.poolDifficulty,
                        blockTimestamp = job.blockTimestamp,
                        extraNonce2 = bytesToHex(padLeftU32(uniqueExtraNonce2, extraNonce2Len)),
                        merkleRoot = "" // computed below
                    )
                }
            }
            lastJobChange = now

            // Clear forgetSubmits so the cycle breaks after one reset
            synchronized(stratumClient) {
                stratumClient.currentJob?.forgetSubmits = false
            }
        }

        // ── Every iteration: increment ExtraNonce2 + recalc MerkleRoot per device ──
        synchronized(stratumJobBuffer) {
            for (i in devices.indices) {
                val devJob = stratumJobBuffer[i]
                if (devJob.id.isEmpty()) continue
                val currentNonce2 = devJob.extraNonce2.toLongOrNull(16) ?: 0L
                val newNonce2 = currentNonce2 + devices.size
                devJob.extraNonce2 = bytesToHex(padLeftU32(newNonce2, extraNonce2Len))
                devJob.merkleRoot = computeMerkleRoot(
                    devJob.coinbasePrefix, devJob.extraNonce1, devJob.extraNonce2,
                    devJob.coinbaseSuffix, devJob.merkleBranches
                )
            }
        }

        // Sleep (Go formula: (4 + AonUserConfig.JobTimer)ms)
        Thread.sleep((4 + AON_JOB_TIMER).toLong())

        // ── Send device-specific jobs with unique ExtraNonce2/MerkleRoot ──
        // Skip sending if disconnected — reconnection at top of loop will refresh
        if (stratumClient.isConnected) {
            synchronized(stratumJobBuffer) {
                for ((i, port) in devices.withIndex()) {
                    val devJob = stratumJobBuffer[i]
                    if (devJob.id.isEmpty()) continue
                    asicSendJob(i, port, devJob)
                }
            }
        }

        // Hashrate report every 60s (log only; notification updates independently every 5s)
        if (now - lastReport >= 60_000) {
            reportHashrate()
            lastReport = now
        }
    }
}

// ── ASIC Send Job ─────────────────────────────────────────────

/** Matches Go's AsicSendJob exactly — takes StratumJob, builds BMJob inside. */
private fun asicSendJob(devID: Int, port: UsbPort, stratumJob: StratumJob) {
    val bmJob = bmConstructJob(stratumJob)

    // Build command: preamble + type|group|cmd + length + jobID + midstateCount
    val cmd = mutableListOf<Byte>()
    cmd.addAll(listOf(BM_PREAMBLE[0], BM_PREAMBLE[1]))
    cmd.add((BM_TYPE_JOB or BM_GROUP_SINGLE or BM_CMD_WRITE).toByte())
    cmd.add(0x36) // length
    cmd.add(bmJob.jobID)
    cmd.add(0x04) // midstateCount
    cmd.addAll(bmJob.startingNonce.toList())
    cmd.addAll(bmJob.networkTarget.toList())
    cmd.addAll(bmJob.blockTimestamp.toList())
    cmd.addAll(bmJob.merkleRoot.toList())
    cmd.addAll(bmJob.prevBlockHash.toList())
    cmd.addAll(bmJob.version.toList())

    val data = cmd.toByteArray()
    val crc = crc16False(data.copyOfRange(2, data.size))
    val fullCmd = data + byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())

    val n = usbWrite(port, fullCmd)
    if (debugMode) onLog("> [port ${port.info.port}] TX job ${bmJob.jobID.toInt()}: ${bytesToHex(fullCmd)}")
    if (n < 0) {
        val errs = writeErrors.incrementAndGet()
        if (errs > 5) {
            onLog("> ${t("too many write errors, disconnecting devices","muitos erros de escrita, desconectando dispositivos")}")
            needsRestart = true
            return
        }
    } else {
        writeErrors.set(0)
    }

    // Track pending job — copy so buffer mutations don't overwrite in-flight data.
    // Go does this by value copy: drivers.StratumJob(stratumJobBuffer[i])
    val frozen = stratumJob.copy()
    bmJob.stratumJob = frozen
    synchronized(pendingJobs) {
        if (devID < pendingJobs.size) {
            pendingJobs[devID].add(bmJob)
            if (pendingJobs[devID].size > 14) {
                pendingJobs[devID] = pendingJobs[devID].takeLast(14).toMutableList()
            }
        }
        if (devID < stratumJobs.size) {
            stratumJobs[devID] = frozen
        }
    }
}

// ── ASIC Read Loop ────────────────────────────────────────────

/** Matches Go's AsicReadJob. */
private fun asicReadLoop(devID: Int, port: UsbPort, stratumClient: StratumClient) {
    while (running.get() && !Thread.interrupted()) {
        if (devices.isEmpty()) return
        Thread.sleep(10)

        val stratumJob = synchronized(pendingJobs) {
            if (devID < stratumJobs.size) stratumJobs[devID] else StratumJob()
        }

        // Check for 120s timeout (bad power)
        if (System.currentTimeMillis() - lastShareTime > 120_000) {
            onLog("> ${t("Bad power! Device(s) stopped responding.","Energia ruim! Dispositivo(s) pararam de responder.")}")
            stopAonMiner()
            return
        }

        // 30ms timeout: matches Go's 10ms × 3 for Android USB jitter headroom.
        val buf = usbRead(port, 11, 30)
        if (buf.isEmpty()) continue

        // Pad to minimum decodeable size (matches Go's 12-byte padding)
        val padded = if (buf.size < 12) buf + ByteArray(12 - buf.size) else buf

        // Check for job response (at least 9 bytes: preamble+cmd+nonce+jobID)
        if (padded.size >= 9 && padded[0] != 0.toByte()) {
            val i = 0 // index into buffer
            // Check preamble (reversed on response: AA 55)
            if (padded[i] == BM_PREAMBLE[1] && padded[i + 1] == BM_PREAMBLE[0]) {
                val workID = ((padded[i + 7].toInt() and 0xF0) shr 1).toByte()
                val pendingForDev = synchronized(pendingJobs) {
                    if (devID < pendingJobs.size) pendingJobs[devID].toList() else emptyList()
                }
                for (pendingJob in pendingForDev) {
                    if (workID != pendingJob.jobID) continue
                    val sJob = pendingJob.stratumJob ?: continue
                    try {
                        val (nonce, version, coreID) = bmDecodeWork(padded)
                        val diff = sha256dValidator(sJob, nonce, version)
                        if (debugMode) onLog("> [port ${port.info.port}] job ${workID.toInt()} nonce=${bytesToHex(nonce)} ver=${bytesToHex(version)} diff=${String.format("%.0f", diff)}")

                        if (diff < 1) hwErrors.addAndGet(255)
                        if (diff >= 255) {
                            synchronized(workTotal) {
                                if (devID < workTotal.size) workTotal[devID] += 255
                            }
                            lastShareTime = System.currentTimeMillis()
                        }
                        if (diff >= stratumClient.poolDifficulty) {
                            val blockVersion = stringToHex(sJob.blockVersion)
                            val baseVer = ((blockVersion[0].toInt() and 0xFF shl 24) or
                                    (blockVersion[1].toInt() and 0xFF shl 16) or
                                    (blockVersion[2].toInt() and 0xFF shl 8) or
                                    (blockVersion[3].toInt() and 0xFF)).toLong()
                            val rolledVer = ((version[0].toInt() and 0xFF shl 24) or
                                    (version[1].toInt() and 0xFF shl 16) or
                                    (version[2].toInt() and 0xFF shl 8) or
                                    (version[3].toInt() and 0xFF)).toLong()
                            val xorVer = baseVer xor rolledVer

                            val work = AonWork(
                                id = stratumJob.id,
                                workID = bytesToHex(byteArrayOf(workID, devID.toByte())),
                                nonce = bytesToHex(nonce),
                                difficulty = diff,
                                version = bytesToHex(uint32ToBytes(xorVer)),
                                extraNonce2 = sJob.extraNonce2,
                                blockTimestamp = sJob.blockTimestamp
                            )
                            synchronized(finishedWorks) { finishedWorks.add(work) }
                            if (diff > bestShare) bestShare = diff
                        }
                    } catch (_: Exception) { }
                    break
                }
            }
        }
    }
}

// ── Work Decoding ─────────────────────────────────────────────

/** Matches Go's bmDecodeWork for BM1368. */
private fun bmDecodeWork(data: ByteArray): Triple<ByteArray, ByteArray, Int> {
    val jobID = ((data[7].toInt() and 0xF0) shr 1)
    val versionBytes = byteArrayOf(data[8], data[9])
    val nonceBytes = data.copyOfRange(2, 6)

    // Nonce is LE uint32 from ASIC
    val nonceValue = ((nonceBytes[0].toInt() and 0xFF)) or
            ((nonceBytes[1].toInt() and 0xFF) shl 8) or
            ((nonceBytes[2].toInt() and 0xFF) shl 16) or
            ((nonceBytes[3].toInt() and 0xFF) shl 24)

    val coreGroup = ((nonceValue shr 25) and 0x7F)
    val coreID = (jobID and 0x0F)

    // Version is BE uint16 from ASIC
    val versionValue = ((versionBytes[0].toInt() and 0xFF) shl 8) or
            (versionBytes[1].toInt() and 0xFF)
    val versionBits = (versionValue.toLong() shl 13)

    // Build version as BE uint32 then pad/preserve it
    val fullVersion = (0x20000000L or versionBits)

    val version = byteArrayOf(
        ((fullVersion shr 24) and 0xFF).toByte(),
        ((fullVersion shr 16) and 0xFF).toByte(),
        ((fullVersion shr 8) and 0xFF).toByte(),
        (fullVersion and 0xFF).toByte()
    )

    // Nonce: Go does ReverseBytes(PadLeft(binary.BigEndian.Uint32(nonceBytes), 4))
    // which is equivalent to reverseBytes(nonceBytes)
    val nonce = reverseBytes(nonceBytes)

    val totalCoreID = coreGroup * 15 + coreID
    return Triple(nonce, version, totalCoreID)
}

// ── Submit Loop ────────────────────────────────────────────────

private fun submitLoop(stratumClient: StratumClient) {
    while (running.get()) {
        val toSubmit: List<AonWork>
        synchronized(finishedWorks) {
            toSubmit = finishedWorks.toList()
            finishedWorks.clear()
        }
        if (toSubmit.isNotEmpty() && stratumClient.isConnected) {
            for (work in toSubmit) {
                stratumClient.submit(
                    id = work.id,
                    workID = work.workID,
                    extraNonce2 = work.extraNonce2,
                    blockTimestamp = work.blockTimestamp,
                    nonce = work.nonce,
                    version = work.version,
                    difficulty = work.difficulty
                )
            }
        }
        Thread.sleep(100)
    }
}

// ── Hashrate Report ───────────────────────────────────────────

private fun reportHashrate() {
    val now = System.currentTimeMillis()
    val factor = 4_294_967_296.0 // 2^32
    var totalGh = 0.0

    for ((i, port) in devices.withIndex()) {
        val elapsed = ((now - workTimers[i]) / 1000.0).coerceAtLeast(1.0)
        val wt = synchronized(workTotal) { if (i < workTotal.size) workTotal[i] else 0 }
        val gh = wt.toDouble() * factor / elapsed / 1e9
        val sn = port.info.serialNumber.padEnd(10, '0').take(10)
        onLog(" ${port.info.productName} Port ${port.info.port}: %.2f GH/s (Avg)".format(gh))
        totalGh += gh
    }

    val formatted = if (totalGh >= 1000) String.format("%.3f TH/s", totalGh / 1000)
    else String.format("%.2f GH/s", totalGh)
    val bestFormatted = formatDiff(bestShare)
    onLog("─────────────────────────────────────")
    onLog(" ${t("Hashrate:","Hashrate:")} $formatted    ${t("Best Share:","Melhor Share:")} $bestFormatted")
    onLog("─────────────────────────────────────")
}

// ── PLL Calculation ───────────────────────────────────────────

/** Matches Go's calculatePLL function — returns a 10-byte command for freq register set. */
private fun calculatePLL(targetFreq: Double, fbDivMin: Int, fbDivMax: Int): ByteArray {
    val params = pllGetParameters(targetFreq, fbDivMin, fbDivMax)

    val vdoScale: Byte = if (25.0 * params.fbDivider / params.refDiv >= 2400) 0x50 else 0x40
    val postDiv = (((params.postDiv1 - 1) and 0xF) shl 4) or ((params.postDiv2 - 1) and 0xF)

    return byteArrayOf(
        0x55.toByte(), 0xAA.toByte(), 0x51, 0x09, 0x00, 0x08,
        vdoScale,
        params.fbDivider.toByte(),
        params.refDiv.toByte(),
        postDiv.toByte()
    )
}

private data class PLLParams(
    val fbDivider: Int,
    val refDiv: Int,
    val postDiv1: Int,
    val postDiv2: Int,
    val actualFreq: Double
)

/** Matches Go's pllGetParameters2. */
private fun pllGetParameters(targetFreq: Double, fbDivMin: Int, fbDivMax: Int): PLLParams {
    var bestFreq = 0.0
    var bestRefDiv = 0
    var bestFbDiv = 0
    var bestPostDiv1 = 0
    var bestPostDiv2 = 0
    var minDiff = Double.MAX_VALUE
    var minVcoFreq = Double.MAX_VALUE
    var minPostDiv = Int.MAX_VALUE

    for (refDiv in 2 downTo 1) {
        for (postDiv1 in 7 downTo 1) {
            for (postDiv2 in 7 downTo 1) {
                val divider = refDiv * postDiv1 * postDiv2
                val fbDiv = Math.round(targetFreq / 25.0 * divider).toInt()
                if (postDiv1 > postDiv2 && fbDiv in fbDivMin..fbDivMax) {
                    val newFreq = 25.0 * fbDiv / divider
                    val currDiff = Math.abs(targetFreq - newFreq)
                    val vcoFreq = 25.0 * fbDiv / refDiv
                    if (currDiff < minDiff ||
                        (Math.abs(currDiff - minDiff) < 0.0001 && vcoFreq < minVcoFreq) ||
                        (Math.abs(currDiff - minDiff) < 0.0001 &&
                                Math.abs(vcoFreq - minVcoFreq) < 0.0001 &&
                                postDiv1 * postDiv2 < minPostDiv)
                    ) {
                        minDiff = currDiff
                        minVcoFreq = vcoFreq
                        minPostDiv = postDiv1 * postDiv2
                        bestFreq = newFreq
                        bestRefDiv = refDiv
                        bestFbDiv = fbDiv
                        bestPostDiv1 = postDiv1
                        bestPostDiv2 = postDiv2
                    }
                }
            }
        }
    }
    return PLLParams(bestFbDiv, bestRefDiv, bestPostDiv1, bestPostDiv2, bestFreq)
}

/** Helper: decode hex string to bytes. */
private fun hexToBytes(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
