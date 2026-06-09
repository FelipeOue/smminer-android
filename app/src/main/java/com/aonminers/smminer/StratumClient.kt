package com.aonminers.smminer

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

data class StratumJob(
    var id: String = "",
    var prevBlockHash: String = "",
    var coinbasePrefix: String = "",
    var extraNonce1: String = "",
    var coinbaseSuffix: String = "",
    var merkleBranches: List<String> = emptyList(),
    var blockVersion: String = "",
    var networkTarget: String = "",
    var poolDifficulty: String = "",
    var blockTimestamp: String = "",
    var forgetSubmits: Boolean = false,
    var extraNonce2: String = "",
    var merkleRoot: String = ""
)

class StratumClient(
    private val poolUrl: String,
    private val username: String,
    private val password: String,
    private val suggestDifficulty: Double,
    private val debug: Boolean,
    private val lang: Language,
    private val onLog: (String) -> Unit
) : Thread() {

    @Volatile var currentJob: StratumJob? = null
        private set

    @Volatile var isConnected = false
        private set

    @Volatile var poolDifficulty = 1.0
        private set

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    @Volatile private var extraNonce1 = ""
    @Volatile internal var extraNonce2Len = 4  // read by the miner loop
    private var rejections = 0
    private var lastBlockHeight = -1L
    private val lastSubmits = mutableListOf<Pair<String, AonWork>>() // workID -> work

    @Volatile private var shutdown = false

    // ── tiny inline translator: only status/error messages ──────
    private fun t(en: String, pt: String, vararg args: Any?): String {
        val tpl = if (lang == Language.PT) pt else en
        return if (args.isEmpty()) tpl else String.format(tpl, *args)
    }
    private fun log(msg: String) = onLog(msg)

    // ── Persistent run loop: auto-reconnects on disconnect ─────
    override fun run() {
        while (!shutdown && !isInterrupted) {
            try {
                log("> ${t("connecting to","conectando a")} $poolUrl...")
                if (!connect()) {
                    try { sleep(2000) } catch (_: InterruptedException) {}
                    continue
                }
                log("> ${t("connected.","conectado.")}")
                if (!subscribe()) { disconnect(); try { sleep(2000) } catch (_: InterruptedException) {}; continue }
                if (!authorize()) { disconnect(); try { sleep(2000) } catch (_: InterruptedException) {}; continue }
                suggestDiff()
                receiverLoop()
            } catch (e: InterruptedException) {
                // Interrupted by reconnect() or shutdown() — loop condition handles exit
            } catch (e: Exception) {
                log("> ${t("stratum error:","erro stratum:")} ${e.message}")
            }
            disconnect()
            log("> ${t("disconnected.","desconectado.")}")
            // backoff before auto-reconnect; reconnect()/shutdown() interrupts this sleep
            try { sleep(2000) } catch (_: InterruptedException) {}
        }
    }

    // ── reconnection ───────────────────────────────────────────
    fun reconnect(): Boolean {
        if (isConnected) return true
        try { socket?.close() } catch (_: Exception) {}
        interrupt()
        var waited = 0
        while (waited < 30_000 && !shutdown && !isConnected && isAlive) {
            try { sleep(200) } catch (_: InterruptedException) { break }
            waited += 200
        }
        return isConnected
    }

    /** Clean shutdown: sets flag so run() exits its loop, then interrupts. */
    fun shutdown() {
        shutdown = true
        interrupt()
    }

    private fun connect(retries: Int = 5): Boolean {
        val (host, port) = parseHostPort(poolUrl)
        for (tries in 1..retries) {
            if (isInterrupted) return false
            try {
                socket = Socket()
                socket!!.connect(InetSocketAddress(host, port), 5000)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
                isConnected = true
                return true
            } catch (e: Exception) {
                log("> ${t("connection attempt","tentativa de conexão")} $tries/$retries ${t("failed, retrying...","falhou, tentando novamente...")}")
                if (tries < retries) sleep(2000)
            }
        }
        log("> ${t("failed to connect after","falha ao conectar após")} $retries ${t("attempts","tentativas")}")
        return false
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val clean = raw.replace("stratum+tcp://", "")
        val idx = clean.lastIndexOf(':')
        if (idx == -1) return Pair(clean, 3333)
        val port = clean.substring(idx + 1).toIntOrNull() ?: 3333
        return Pair(clean.substring(0, idx), port)
    }

    // ── subscribe ──────────────────────────────────────────────
    private fun subscribe(): Boolean {
        val isReconnection = extraNonce1.isNotEmpty()
        val params = JSONArray().apply { put("NerdMinerV2") }
        if (isReconnection) {
            log("> ${t("trying previous session...","tentando sessão anterior...")}")
            params.put(extraNonce1)
        }
        if (!stratumWrite("FFFF", "mining.subscribe", params)) return false

        val response = stratumRead() ?: return false
        val result = response.optJSONArray("result")
        if (result == null || result.length() == 0) {
            log("> ${t("pool rejected connection or sent bad response","pool rejeitou conexão ou enviou resposta inválida")}")
            return false
        }

        val sub0 = result.getJSONArray(0).getJSONArray(0)
        extraNonce1 = result.getString(1)
        extraNonce2Len = result.getInt(2)

        // parse pool difficulty from subscription
        if (sub0.length() > 1 && !sub0.isNull(1)) {
            poolDifficulty = sub0.optString(1, "1").toDoubleOrNull() ?: 1.0
        }
        if (poolDifficulty <= 0) {
            log("> ${t("pool sent invalid difficulty, defaulting to 1","pool enviou dificuldade inválida, usando 1")}")
            poolDifficulty = 1.0
        }
        log("> ${t("pool default difficulty:","dificuldade padrão da pool:")} $poolDifficulty")
        return true
    }

    // ── authorize ──────────────────────────────────────────────
    private fun authorize(): Boolean {
        val params = JSONArray().apply { put(username); put(password) }
        if (!stratumWrite("FFFF", "mining.authorize", params)) return false

        val response = stratumRead() ?: return false
        val result = response.optJSONArray("result")
        val notifyParams = response.optJSONArray("params")

        // pool may send mining.notify before answering authorize
        if ((result == null || result.length() == 0) && notifyParams != null && notifyParams.length() > 0) {
            log("> ${t("pool skipped credential validation","pool pulou validação de credenciais")}")
            return true
        }
        if (result != null && result.length() > 0 && result.optBoolean(0, false)) {
            log("> ${t("user","usuário")} $username ${t("authenticated","autenticado")}")
            return true
        }
        log("> ${t("authentication failed","autenticação falhou")}")
        return false
    }

    // ── suggest difficulty ─────────────────────────────────────
    private fun suggestDiff() {
        log("> ${t("suggesting difficulty","sugerindo dificuldade")} $suggestDifficulty")
        stratumWrite("FFFF", "mining.suggest_difficulty",
            JSONArray().apply { put(suggestDifficulty) })
    }

    // ── receiver loop ──────────────────────────────────────────
    private fun receiverLoop() {
        while (!isInterrupted && isConnected) {
            val line: String
            try {
                line = reader?.readLine() ?: break
            } catch (e: Exception) {
                if (isConnected) log("> ${t("read error:","erro de leitura:")} ${e.message}")
                break
            }
            val resp = parseResponse(line)
            val method = if (resp.has("method")) resp.getString("method") else null
            val params = resp.optJSONArray("params")
            val id = if (resp.has("id")) resp.getString("id") else null
            val result = resp.optJSONArray("result")

            // raw responses only in debug mode
            if (debug && method != "mining.notify") log("<- $line")

            synchronized(this) {
                when (method) {
                    "mining.notify" -> {
                        if (params != null && params.length() >= 9) processNotify(params)
                    }
                    "mining.set_difficulty" -> {
                        if (params != null && params.length() > 0) {
                            poolDifficulty = params.getDouble(0)
                            currentJob?.poolDifficulty = String.format("%.3f", poolDifficulty)
                            log("> ${t("difficulty set to","dificuldade alterada para")} $poolDifficulty")
                        }
                    }
                }
                // non-FFFF id = submit response — match against tracked submits
                if (id != null && id != "FFFF" && id.isNotEmpty()) {
                    val idx = lastSubmits.indexOfFirst { it.first == id }
                    val matched = if (idx >= 0) lastSubmits.removeAt(idx) else null

                    // Go silently ignores responses for unknown workIDs — skip logging
                    if (matched != null) {
                        val d = matched.second.difficulty
                        val shareDiffStr = if (poolDifficulty > 1) formatDiff(d) else String.format("%.0f", d)
                        val nonceStr = matched.second.nonce

                        if (result != null && result.length() > 0 && !result.isNull(0)) {
                            if (result.optBoolean(0, false)) {
                                log("Share accepted $nonceStr $shareDiffStr/${formatDiff(poolDifficulty)}")
                                rejections = 0
                            } else {
                                log("Share rejected $nonceStr $shareDiffStr/${formatDiff(poolDifficulty)}")
                                val err = resp.optJSONArray("error")
                                if (err != null && err.length() > 1 && !err.isNull(0)) {
                                    log("Pool error message: ${err.optString(0)}")
                                }
                                rejections++
                            }
                        } else {
                            log("Share rejected (Stale) $nonceStr $shareDiffStr/${formatDiff(poolDifficulty)}")
                            rejections++
                        }
                    }
                }

                // Too many rejections → force job change
                if (rejections > 3) {
                    log("${t("Too many rejections, forcing job change...","Muitas rejeições, forçando troca de trabalho...")}")
                    rejections = 0
                    currentJob?.forgetSubmits = true
                }
            }
        }
        isConnected = false
    }

    private fun processNotify(params: JSONArray) {
        try {
            if (params.length() < 9) return
            val branches = mutableListOf<String>()
            val arr = params.getJSONArray(4)
            for (i in 0 until arr.length()) branches.add(arr.getString(i))

            if (branches.isEmpty() || params.getString(1).isEmpty()) return

            // refresh job fields but preserve existing forgetSubmits
            val job = currentJob ?: StratumJob()
            job.id = params.getString(0)
            job.prevBlockHash = params.getString(1)
            job.coinbasePrefix = params.getString(2)
            job.coinbaseSuffix = params.getString(3)
            job.merkleBranches = branches
            job.blockVersion = params.getString(5)
            job.networkTarget = params.getString(6)
            job.blockTimestamp = params.getString(7)
            // forgetSubmits is set unconditionally, matching Go's StratumJob struct replacement
            job.forgetSubmits = params.optBoolean(8, false)
            job.extraNonce1 = extraNonce1
            job.extraNonce2 = "00".repeat(extraNonce2Len)
            job.poolDifficulty = poolDifficulty.toString()
            // compute merkleRoot from coinbase + branches
            job.merkleRoot = computeMerkleRoot(
                job.coinbasePrefix, job.extraNonce1, job.extraNonce2,
                job.coinbaseSuffix, job.merkleBranches
            )
            currentJob = job

            // Block height detection (matches Go's LastBlockHeight)
            if (job.coinbasePrefix.length >= 92) {
                val heightHex = job.coinbasePrefix.substring(86, 92)
                val height = try { reverseHexString(heightHex).toLong(16) } catch (_: Exception) { -1L }
                if (height > 0 && height != lastBlockHeight) {
                    log("${t("New block detected at height","Novo bloco detectado na altura")} ${height - 1}")
                }
                lastBlockHeight = height
            }

            // Verbose job dump only in debug mode
            if (debug) {
                val branchPreview = branches.take(3).joinToString(", ") { it.take(12) + "..." }
                val sb = StringBuilder()
                sb.appendLine("── job received ──")
                sb.appendLine("  id:               ${job.id}")
                sb.appendLine("  prevBlockHash:    ${job.prevBlockHash}")
                sb.appendLine("  coinbasePrefix:   ${job.coinbasePrefix.take(40)}...")
                sb.appendLine("  coinbaseSuffix:   ${job.coinbaseSuffix.take(40)}...")
                sb.appendLine("  merkleBranches:   ${branches.size} branch(es) [ $branchPreview ... ]")
                sb.appendLine("  blockVersion:     ${job.blockVersion}")
                sb.appendLine("  networkTarget:    ${job.networkTarget}")
                sb.appendLine("  blockTimestamp:   ${job.blockTimestamp}")
                sb.appendLine("  forgetSubmits:    ${job.forgetSubmits}")
                sb.appendLine("  extraNonce1:      ${job.extraNonce1}")
                sb.appendLine("  extraNonce2:      ${job.extraNonce2}")
                sb.appendLine("  poolDifficulty:   ${job.poolDifficulty}")
                sb.append("───────────────────")
                log(sb.toString())
            }
        } catch (_: Exception) { }
    }

    // ── I/O helpers ────────────────────────────────────────────
    private fun stratumWrite(id: String, method: String, params: JSONArray): Boolean {
        val json = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        if (debug) log("-> $json")
        return try {
            writer?.write(json + "\n")
            writer?.flush()
            true
        } catch (e: Exception) {
            isConnected = false
            if (debug) log("> ${t("write error:","erro de escrita:")} ${e.message}")
            false
        }
    }

    private fun stratumRead(): JSONObject? {
        val line: String
        try {
            line = reader?.readLine() ?: return null
        } catch (e: Exception) {
            log("> ${t("read error:","erro de leitura:")} ${e.message}")
            return null
        }
        if (debug) log("<- $line")
        return parseResponse(line)
    }

    private fun parseResponse(raw: String): JSONObject {
        val fixed = raw
            .replace(Regex("\"result\"\\s*:\\s*(true|false|null)(?=\\s*[,}\\]])"), "\"result\":[$1]")
            .replace(Regex("\"error\"\\s*:\\s*(true|false|null)(?=\\s*[,}\\]])"), "\"error\":[$1]")
        return try { JSONObject(fixed) } catch (_: Exception) { JSONObject() }
    }

    // ── submit ────────────────────────────────────────────────
    fun submit(id: String, workID: String, extraNonce2: String,
               blockTimestamp: String, nonce: String, version: String,
               difficulty: Double = 0.0) {
        val params = JSONArray().apply {
            put(username)
            put(id)
            put(extraNonce2)
            put(blockTimestamp)
            put(nonce)
            put(version)
        }
        // Track for response matching (matches Go's LastStratumSubmits)
        synchronized(this) {
            lastSubmits.add(workID to AonWork(
                id = id, workID = workID, nonce = nonce,
                difficulty = difficulty, version = version,
                extraNonce2 = extraNonce2, blockTimestamp = blockTimestamp
            ))
            if (lastSubmits.size > 10) lastSubmits.removeAt(0)
        }
        stratumWrite(workID, "mining.submit", params)
    }

    fun disconnect() {
        isConnected = false
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        reader = null
        socket = null
    }
}
