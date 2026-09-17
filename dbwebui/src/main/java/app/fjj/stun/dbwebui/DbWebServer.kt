package app.fjj.stun.dbwebui

import android.content.Context
import app.fjj.stun.repo.AppDatabase
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通用 SQLite Web 管理台：独立 ktor 服务，跑在自己的端口（不与节点 WebServer 混用）。
 *
 * 设计要点（安全优先）：
 * - 复用 Room 的同一底层连接（openHelper.writableDatabase），读写走同一 WAL，避免
 *   第二条连接争锁/脏读；对 profiles 这类业务表的编辑与手机端保持一致。
 * - 会话登录：用户名/密码在应用设置里配置（密码 Keystore 加密落盘）；登录成功签发
 *   HttpOnly SameSite=Strict 会话 Cookie，内存态 token→过期时间，12h TTL。
 * - 暴力破解限制：按来源 IP 计数，连续 5 次失败锁 30s。
 * - 任意 SQL：满足 power-user 需求，SELECT/PRAGMA 走只读语义返回结果集，其余 execSQL。
 *   注意：可执行 DROP/ALTER 破坏 Room identity_hash，UI 有醒目提示但仍按用户选择开放。
 */
object DbWebServer {
    private const val TAG = "DbWebServer"
    private const val SESSION_COOKIE = "dbweb_session"
    private const val SESSION_TTL_MS = 12L * 60 * 60 * 1000
    private const val MAX_SQL_ROWS = 1000
    private const val BACKUP_DIR_NAME = "dbweb_backups"
    private const val MAX_BACKUPS = 10
    private val BACKUP_FILE_RE = Regex("^[A-Za-z0-9._\\-]{1,80}\\.json$")
    private val BACKUP_FORMAT = "stun-dbweb-backup/1"

    private val gson = Gson()
    private val rng = SecureRandom()
    internal lateinit var appCtx: Context   // set in start(); consumed by the top-level dbWebModule

    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile private var isRunning = false
    var actualPort: Int = SettingsManager.DEFAULT_DB_WEB_PORT; private set

    private val sessions = ConcurrentHashMap<String, Long>()          // token -> expiry
    private val loginFails = ConcurrentHashMap<String, Long>()        // ip -> lockUntil
    private val loginCounts = ConcurrentHashMap<String, Int>()        // ip -> consecutive fails

    // ── lifecycle ──────────────────────────────────────────────
    fun start(context: Context, port: Int = SettingsManager.getDbWebPort(context)): Int {
        if (isRunning) return actualPort
        appCtx = context.applicationContext
        actualPort = try {
            ServerSocket(port).use { it.localPort }
        } catch (_: Exception) {
            ServerSocket(0).use { it.localPort }
        }
        // Pass the module as a plain lambda (NOT a method reference / KFunction). A `KFunction`
        // makes ktor reflect on it via kotlin-reflect in `ServerHostUtilsKt.methodName`; R8 strips
        // the top-level file-facade's @Metadata in release builds, so that reflection throws
        // "no members found" and crashes DbWebServer.start(). A lambda is a FunctionN (not a
        // KFunction), so ktor skips reflection and falls back to calling module(application)
        // directly — the same pattern StunMcpServer uses and which survives release minification.
        server = embeddedServer(CIO, port = actualPort, watchPaths = emptyList()) {
            dbWebModule()
        }
        try {
            server?.start(wait = false)
            isRunning = true
            StunLogger.i(TAG, "DbWebServer started on port $actualPort")
        } catch (e: Exception) {
            StunLogger.e(TAG, "DbWebServer start failed: ${e.message}")
            isRunning = false
        }
        return actualPort
    }

    fun stop() {
        try { server?.stop(200, 500) } catch (_: Exception) {}
        server = null
        isRunning = false
        sessions.clear()
    }

    fun restart(context: Context, port: Int = SettingsManager.getDbWebPort(context)) { stop(); start(context, port) }
    fun isRunning() = isRunning

    fun getLocalIp(context: Context): String = app.fjj.stun.remote.WebServer.getLocalIp(context)

    fun getEffectiveUrl(context: Context, port: Int = actualPort): String {
        val ip = getLocalIp(context)
        return "http://$ip:$port/"
    }

    // ── auth ───────────────────────────────────────────────────
    internal suspend fun ApplicationCall.respondAsset(ctx: Context, path: String, type: ContentType) {
        try {
            val text = ctx.assets.open(path).bufferedReader().use { it.readText() }
            respondText(text, type)
        } catch (e: Exception) {
            respondText("404 — asset missing: ${e.message}", status = HttpStatusCode.NotFound)
        }
    }

    private fun ApplicationCall.ip(): String = try { request.local.remoteHost } catch (_: Exception) { "unknown" }

    private fun newToken(): String = ByteArray(24).also { rng.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    // Parse the session token straight from the Cookie header (avoids relying on the
    // Cookies plugin's server-side accessor API).
    private fun sessionToken(call: ApplicationCall): String? {
        val raw = call.request.headers["Cookie"] ?: return null
        return raw.split(';').asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }

    private fun isValidSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val exp = sessions[token] ?: return false
        if (exp < System.currentTimeMillis()) { sessions.remove(token); return false }
        return true
    }

    internal suspend fun requireSession(call: ApplicationCall, block: suspend () -> Unit) {
        if (!isValidSession(sessionToken(call))) {
            call.respondText(gson.toJson(errorObj("unauthorized")), ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        block()
    }

    internal suspend fun handleMe(ctx: Context, call: ApplicationCall) {
        if (!isValidSession(sessionToken(call))) {
            call.respondText(gson.toJson(errorObj("unauthorized")), ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        val o = JsonObject(); o.addProperty("ok", true); o.addProperty("user", SettingsManager.getDbWebUser(ctx))
        call.respondText(gson.toJson(o), ContentType.Application.Json)
    }

    internal suspend fun handleLogin(ctx: Context, call: ApplicationCall) {
        val ip = call.ip()
        val lockUntil = loginFails[ip] ?: 0L
        if (lockUntil > System.currentTimeMillis()) {
            val o = errorObj("too_many_attempts"); o.addProperty("retryInSec", ((lockUntil - System.currentTimeMillis()) / 1000) + 1)
            call.respondText(gson.toJson(o), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
            return
        }
        val body = try { gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject() }
                   catch (_: Exception) { call.respondText(gson.toJson(errorObj("bad_request")), ContentType.Application.Json, HttpStatusCode.BadRequest); return }
        val user = (body.get("user")?.asString ?: "").trim()
        val pass = body.get("pass")?.asString ?: ""
        val expUser = SettingsManager.getDbWebUser(ctx)
        val expPass = SettingsManager.getDbWebPass(ctx)
        if (constantTimeEquals(user, expUser) && constantTimeEquals(pass, expPass)) {
            loginCounts.remove(ip); loginFails.remove(ip)
            val token = newToken()
            sessions[token] = System.currentTimeMillis() + SESSION_TTL_MS
            call.response.header(HttpHeaders.SetCookie,
                "$SESSION_COOKIE=$token; Max-Age=${SESSION_TTL_MS / 1000}; HttpOnly; Path=/; SameSite=Strict")
            val o = JsonObject(); o.addProperty("ok", true); o.addProperty("user", expUser)
            call.respondText(gson.toJson(o), ContentType.Application.Json)
        } else {
            val count = (loginCounts[ip] ?: 0) + 1
            loginCounts[ip] = count
            if (count >= 5) { loginFails[ip] = System.currentTimeMillis() + 30_000; loginCounts.remove(ip) }
            call.respondText(gson.toJson(errorObj("invalid_credentials")), ContentType.Application.Json, HttpStatusCode.Unauthorized)
        }
    }

    internal suspend fun handleLogout(call: ApplicationCall) {
        sessionToken(call)?.let { sessions.remove(it) }
        call.response.header(HttpHeaders.SetCookie, "$SESSION_COOKIE=; Max-Age=0; HttpOnly; Path=/; SameSite=Strict")
        val o = JsonObject(); o.addProperty("ok", true)
        call.respondText(gson.toJson(o), ContentType.Application.Json)
    }

    // ── DB helpers ─────────────────────────────────────────────
    private fun db(ctx: Context): SupportSQLiteDatabase = AppDatabase.getDatabase(ctx).openHelper.writableDatabase

    private fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private fun listTables(sdb: SupportSQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        sdb.query(SimpleSQLiteQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )).use { c -> (c as? android.database.Cursor)?.let { cur -> while (cur.moveToNext()) out.add(cur.getString(0)) } }
        return out
    }

    private data class Col(val name: String, val type: String, val pk: Int, val notNull: Boolean, val dflt: String?)
    private fun tableCols(sdb: SupportSQLiteDatabase, table: String): List<Col> {
        val cols = ArrayList<Col>()
        sdb.query(SimpleSQLiteQuery("PRAGMA table_info(${quoteIdent(table)})")).use { c ->
            val cur = c as? android.database.Cursor ?: return emptyList()
            val iName = cur.getColumnIndex("name"); val iType = cur.getColumnIndex("type")
            val iPk = cur.getColumnIndex("pk"); val iNn = cur.getColumnIndex("notnull"); val iDflt = cur.getColumnIndex("dflt_value")
            while (cur.moveToNext()) {
                val d = if (iDflt >= 0 && !cur.isNull(iDflt)) cur.getString(iDflt) else null
                cols.add(Col(cur.getString(iName), cur.getString(iType), cur.getInt(iPk), cur.getInt(iNn) != 0, d))
            }
        }
        return cols
    }

    private fun tableCount(sdb: SupportSQLiteDatabase, table: String): Long {
        return sdb.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM ${quoteIdent(table)}")).use { c ->
            val cur = c as? android.database.Cursor; if (cur != null && cur.moveToFirst()) cur.getLong(0) else 0L
        }
    }

    // ── table/schema/rows API ──────────────────────────────────
    internal suspend fun handleTables(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val sdb = db(ctx)
            val arr = JsonArray()
            for (t in listTables(sdb)) {
                val o = JsonObject(); o.addProperty("name", t); o.addProperty("rows", tableCount(sdb, t))
                o.addProperty("columns", tableCols(sdb, t).size)
                arr.add(o)
            }
            val res = JsonObject(); res.add("tables", arr)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleSchema(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        val table = call.request.queryParameters["table"] ?: ""
        if (table !in listTables(db(ctx))) { call.respondText(gson.toJson(errorObj("no_such_table")), ContentType.Application.Json, HttpStatusCode.NotFound); return@withContext }
        val cols = JsonArray()
        for (c in tableCols(db(ctx), table)) {
            val o = JsonObject(); o.addProperty("name", c.name); o.addProperty("type", c.type)
            o.addProperty("pk", c.pk); o.addProperty("notNull", c.notNull); o.addProperty("dflt", c.dflt)
            cols.add(o)
        }
        val res = JsonObject(); res.addProperty("table", table); res.add("columns", cols)
        call.respondText(gson.toJson(res), ContentType.Application.Json)
    }

    internal suspend fun handleRows(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val table = call.request.queryParameters["table"] ?: ""
            val qp = call.request.queryParameters
            val page = (qp["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val size = (qp["size"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
            val searchCol = qp["searchCol"] ?: ""
            val searchTerm = qp["searchTerm"] ?: ""
            val sortBy = qp["sortBy"] ?: ""
            val desc = qp["desc"]?.toBoolean() ?: false

            val sdb = db(ctx)
            if (table !in listTables(sdb)) { call.respondText(gson.toJson(errorObj("no_such_table")), ContentType.Application.Json, HttpStatusCode.NotFound); return@withContext }
            val cols = tableCols(sdb, table)
            val colNames = cols.map { it.name }

            val where = StringBuilder(); val args = ArrayList<Any>()
            if (searchCol.isNotEmpty() && searchTerm.isNotEmpty() && searchCol in colNames) {
                where.append(" WHERE ${quoteIdent(searchCol)} LIKE ?"); args.add("%$searchTerm%")
            }
            val orderBy = StringBuilder()
            if (sortBy.isNotEmpty() && sortBy in colNames) {
                orderBy.append(" ORDER BY ${quoteIdent(sortBy)} ${if (desc) "DESC" else "ASC"}")
            } else {
                // deterministic default order by PK then rowid-ish (first column)
                val pk = cols.firstOrNull { it.pk > 0 } ?: cols.firstOrNull()
                if (pk != null) orderBy.append(" ORDER BY ${quoteIdent(pk.name)} ASC")
            }

            val total = tableCountWhere(sdb, table, where.toString(), args)
            val limitArgs = ArrayList<Any>(args); limitArgs.add(size); limitArgs.add((page - 1) * size)
            val sql = "SELECT * FROM ${quoteIdent(table)}$where$orderBy LIMIT ? OFFSET ?"
            val (columns, rows) = sdb.queryToColumnsAndRows(SimpleSQLiteQuery(sql, limitArgs.toArray()), maxRows = size)

            val res = JsonObject()
            res.addProperty("table", table); res.addProperty("total", total)
            res.addProperty("page", page); res.addProperty("size", size)
            res.add("columns", JsonArray().apply { columns.forEach { add(it) } })
            res.add("pks", JsonArray().apply { cols.filter { it.pk > 0 }.forEach { add(it.name) } })
            res.add("rows", rows)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    private fun tableCountWhere(sdb: SupportSQLiteDatabase, table: String, where: String, args: List<Any>): Long {
        val sql = "SELECT COUNT(*) FROM ${quoteIdent(table)}$where"
        return sdb.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            val cur = c as? android.database.Cursor; if (cur != null && cur.moveToFirst()) cur.getLong(0) else 0L
        }
    }

    // ── row write API ──────────────────────────────────────────
    internal suspend fun handleRowWrite(ctx: Context, call: ApplicationCall, mode: String) = withContext(Dispatchers.IO) {
        try {
            val body = gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject()
            val table = body.get("table")?.asString ?: ""
            val sdb = db(ctx)
            if (table !in listTables(sdb)) { call.respondText(gson.toJson(errorObj("no_such_table")), ContentType.Application.Json, HttpStatusCode.NotFound); return@withContext }
            val cols = tableCols(sdb, table)
            val colNames = cols.map { it.name }.toSet()
            val valuesObj = body.getAsJsonObject("values") ?: JsonObject()

            // Only accept columns that actually exist; drop anything else defensively.
            val setCols = valuesObj.keySet().filter { it in colNames }
            if (setCols.isEmpty()) { call.respondText(gson.toJson(errorObj("no_columns")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }

            if (mode == "insert") {
                val placeholders = setCols.joinToString(",") { "?" }
                val sql = "INSERT INTO ${quoteIdent(table)} (${setCols.joinToString(",") { quoteIdent(it) }}) VALUES ($placeholders)"
                val args = setCols.map { jsonCellToBind(valuesObj.get(it)) }
                val newId = sdb.insertAndReturnRowId(sql, args)
                val res = JsonObject(); res.addProperty("ok", true); res.add("insertedRowId", gson.toJsonTree(newId))
                call.respondText(gson.toJson(res), ContentType.Application.Json)
                return@withContext
            }

            // update requires a PK predicate
            val whereArgs = buildWhereByPk(sdb, table, cols, body)
            if (whereArgs == null) { call.respondText(gson.toJson(errorObj("no_primary_key")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
            val (whereSql, whereVals) = whereArgs
            val setSql = setCols.joinToString(",") { "${quoteIdent(it)}=?" }
            val args = setCols.map { jsonCellToBind(valuesObj.get(it)) } + whereVals
            val changed = sdb.executeUpdate("UPDATE ${quoteIdent(table)} SET $setSql$whereSql", args)
            val res = JsonObject(); res.addProperty("ok", true); res.addProperty("changed", changed)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleRowDelete(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val body = gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject()
            val table = body.get("table")?.asString ?: ""
            val sdb = db(ctx)
            if (table !in listTables(sdb)) { call.respondText(gson.toJson(errorObj("no_such_table")), ContentType.Application.Json, HttpStatusCode.NotFound); return@withContext }
            val cols = tableCols(sdb, table)
            val whereArgs = buildWhereByPk(sdb, table, cols, body)
            if (whereArgs == null) { call.respondText(gson.toJson(errorObj("no_primary_key")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
            val (whereSql, whereVals) = whereArgs
            val changed = sdb.executeUpdate("DELETE FROM ${quoteIdent(table)}$whereSql", whereVals)
            val res = JsonObject(); res.addProperty("ok", true); res.addProperty("changed", changed)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    // Build " WHERE pk1=? AND pk2=?" from body.pk map (validated against schema).
    private fun buildWhereByPk(sdb: SupportSQLiteDatabase, table: String, cols: List<Col>, body: JsonObject): Pair<String, List<Any?>>? {
        val pkCols = cols.filter { it.pk > 0 }.map { it.name }
        val pkObj = body.getAsJsonObject("pk")
        if (pkObj != null && pkCols.isNotEmpty() && pkCols.all { pkObj.has(it) }) {
            val args = pkCols.map { jsonCellToBind(pkObj.get(it)) }
            return (" WHERE " + pkCols.joinToString(" AND ") { "${quoteIdent(it)}=?" }) to args
        }
        // Fallback: rowid for non-WITHOUT-ROWID tables (only if a single "rowid" is provided).
        if (pkObj != null && pkObj.has("_rowid_")) {
            val ok = try { sdb.query(SimpleSQLiteQuery("SELECT rowid FROM ${quoteIdent(table)} LIMIT 0")).use { true } } catch (_: Exception) { false }
            if (ok) return (" WHERE rowid=?") to listOf(jsonCellToBind(pkObj.get("_rowid_")))
        }
        return null
    }

    // ── arbitrary SQL ──────────────────────────────────────────
    internal suspend fun handleSql(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val body = gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject()
            val sql = body.get("sql")?.asString?.trim() ?: ""
            if (sql.isEmpty()) { call.respondText(gson.toJson(errorObj("empty_sql")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
            val sdb = db(ctx)
            val firstWord = sql.trimStart().substringBefore(' ').uppercase()
            val isSelect = firstWord in setOf("SELECT", "PRAGMA", "WITH", "EXPLAIN", "VALUES")
            if (isSelect) {
                val (columns, rows) = sdb.queryToColumnsAndRows(SimpleSQLiteQuery(sql), maxRows = MAX_SQL_ROWS)
                val res = JsonObject(); res.addProperty("ok", true); res.addProperty("kind", "result")
                res.add("columns", JsonArray().apply { columns.forEach { add(it) } })
                res.add("rows", rows)
                call.respondText(gson.toJson(res), ContentType.Application.Json)
            } else {
                sdb.execSQL(sql)
                val res = JsonObject(); res.addProperty("ok", true); res.addProperty("kind", "update"); res.addProperty("message", "OK")
                call.respondText(gson.toJson(res), ContentType.Application.Json)
            }
        } catch (e: Exception) {
            val o = errorObj(e.message ?: "sql_error")
            call.respondText(gson.toJson(o), ContentType.Application.Json, HttpStatusCode.OK) // 200 + ok:false so client shows SQL error inline
        }
    }

    // ── export / import / backups ──────────────────────────────
    private fun backupDir(ctx: Context): File = File(ctx.filesDir, BACKUP_DIR_NAME).apply { mkdirs() }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    internal suspend fun handleExport(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val format = call.request.queryParameters["format"] ?: "json"
            val sdb = db(ctx)
            when (format) {
                "json" -> {
                    val dump = dumpBackupJson(sdb)
                    call.respondDownload("stun-db-${stamp()}.json", gson.toJson(dump).toByteArray(), "application/json")
                }
                "sql" -> call.respondDownload("stun-db-${stamp()}.sql", dumpSql(sdb).toByteArray(), "application/sql")
                "csv" -> {
                    val table = call.request.queryParameters["table"] ?: ""
                    if (table !in listTables(sdb)) {
                        call.respondText(gson.toJson(errorObj("no_such_table")), ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@withContext
                    }
                    call.respondDownload("stun-$table-${stamp()}.csv", dumpCsv(sdb, table).toByteArray(), "text/csv")
                }
                else -> call.respondText(gson.toJson(errorObj("bad_format")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleImport(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val body = gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject()
            val data = body.get("data")?.asString
                ?: throw IllegalArgumentException("missing_data")
            val obj = gson.fromJson(data, JsonObject::class.java) ?: throw IllegalArgumentException("bad_json")
            val res = restoreBackupJson(db(ctx), obj)
            StunLogger.i(TAG, "dbweb import: $res")
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "import_error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleBackupCreate(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val body = try { gson.fromJson(call.receiveText(), JsonObject::class.java) } catch (_: Exception) { null }
            val raw = (body?.get("name")?.asString ?: "").trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)
            val fileName = if (raw.isEmpty()) "backup-${stamp()}.json" else "backup-$raw-${stamp()}.json"
            val dump = dumpBackupJson(db(ctx))
            File(backupDir(ctx), fileName).writeText(gson.toJson(dump))
            // rolling retention: keep the newest MAX_BACKUPS files
            backupDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(MAX_BACKUPS)?.forEach { it.delete() }
            val res = JsonObject(); res.addProperty("ok", true); res.addProperty("file", fileName)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleBackupList(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val arr = JsonArray()
            backupDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    val o = JsonObject(); o.addProperty("file", f.name); o.addProperty("size", f.length())
                    o.addProperty("mtime", f.lastModified())
                    arr.add(o)
                }
            val res = JsonObject(); res.add("backups", arr)
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    private fun resolveBackup(ctx: Context, name: String?): File? {
        if (name == null || !BACKUP_FILE_RE.matches(name)) return null
        val f = File(backupDir(ctx), name)
        return if (f.isFile && f.canonicalPath.startsWith(backupDir(ctx).canonicalPath)) f else null
    }

    internal suspend fun handleBackupDownload(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        val f = resolveBackup(ctx, call.request.queryParameters["file"])
        if (f == null) { call.respondText(gson.toJson(errorObj("bad_file")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
        call.respondDownload(f.name, f.readBytes(), "application/json")
    }

    internal suspend fun handleBackupRestore(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        try {
            val body = gson.fromJson(call.receiveText(), JsonObject::class.java) ?: JsonObject()
            val f = resolveBackup(ctx, body.get("file")?.asString)
            if (f == null) { call.respondText(gson.toJson(errorObj("bad_file")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
            val obj = gson.fromJson(f.readText(), JsonObject::class.java) ?: throw IllegalArgumentException("bad_backup")
            val res = restoreBackupJson(db(ctx), obj)
            StunLogger.i(TAG, "dbweb restore ${f.name}: $res")
            call.respondText(gson.toJson(res), ContentType.Application.Json)
        } catch (e: Exception) { call.respondText(gson.toJson(errorObj(e.message ?: "restore_error")), ContentType.Application.Json, HttpStatusCode.InternalServerError) }
    }

    internal suspend fun handleBackupDelete(ctx: Context, call: ApplicationCall) = withContext(Dispatchers.IO) {
        val body = try { gson.fromJson(call.receiveText(), JsonObject::class.java) } catch (_: Exception) { null }
        val f = resolveBackup(ctx, body?.get("file")?.asString)
        if (f == null) { call.respondText(gson.toJson(errorObj("bad_file")), ContentType.Application.Json, HttpStatusCode.BadRequest); return@withContext }
        val ok = f.delete()
        val res = JsonObject(); res.addProperty("ok", ok); if (!ok) res.addProperty("error", "delete_failed")
        call.respondText(gson.toJson(res), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.respondDownload(fileName: String, bytes: ByteArray, mime: String) {
        response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
        respondBytes(bytes, ContentType.parse(mime))
    }

    /** Full-DB consistent snapshot as JSON: {format, created, tables:[{name, columns, rows}]}.
     *  BLOBs are encoded as {"$b": base64}; rowid is included when the table has one. */
    private fun dumpBackupJson(sdb: SupportSQLiteDatabase): JsonObject {
        val root = JsonObject()
        root.addProperty("format", BACKUP_FORMAT)
        root.addProperty("created", java.util.Date().toString())
        val inTxn = try { sdb.execSQL("BEGIN"); true } catch (_: Exception) { false } // deferred txn = stable WAL read snapshot
        try {
            root.add("tables", dumpTablesJson(sdb))
        } finally {
            if (inTxn) try { sdb.execSQL("COMMIT") } catch (_: Exception) { try { sdb.execSQL("ROLLBACK") } catch (_: Exception) {} }
        }
        return root
    }

    private fun dumpTablesJson(sdb: SupportSQLiteDatabase): JsonArray {
        val tables = JsonArray()
        for (t in listTables(sdb)) {
            val cols = tableCols(sdb, t)
            val colNames = cols.map { it.name }
            val hasRowid = try { sdb.query(SimpleSQLiteQuery("SELECT rowid FROM ${quoteIdent(t)} LIMIT 0")).use { true } } catch (_: Exception) { false }
            val sql = if (hasRowid) "SELECT rowid AS _rowid_, * FROM ${quoteIdent(t)}" else "SELECT * FROM ${quoteIdent(t)}"
            val out = JsonObject()
            out.addProperty("name", t)
            out.add("columns", JsonArray().apply { if (hasRowid) add("_rowid_"); colNames.forEach { add(it) } })
            val rows = JsonArray()
            sdb.query(SimpleSQLiteQuery(sql)).use { c ->
                val cur = c as? android.database.Cursor ?: return@use
                val n = cur.columnCount
                while (cur.moveToNext()) {
                    val row = JsonArray()
                    for (i in 0 until n) row.add(cursorCellToJson(cur, i))
                    rows.add(row)
                }
            }
            out.add("rows", rows)
            tables.add(out)
        }
        return tables
    }

    private fun cursorCellToJson(cur: android.database.Cursor, i: Int): com.google.gson.JsonElement {
        if (cur.isNull(i)) return com.google.gson.JsonNull.INSTANCE
        return when (cur.getType(i)) {
            android.database.Cursor.FIELD_TYPE_INTEGER -> gson.toJsonTree(cur.getLong(i))
            android.database.Cursor.FIELD_TYPE_FLOAT -> gson.toJsonTree(cur.getDouble(i))
            android.database.Cursor.FIELD_TYPE_BLOB -> JsonObject().apply {
                addProperty("\$b", Base64.getEncoder().encodeToString(cur.getBlob(i)))
            }
            else -> gson.toJsonTree(cur.getString(i))
        }
    }

    private fun sqlLiteral(el: com.google.gson.JsonElement?): String = when {
        el == null || el.isJsonNull -> "NULL"
        el is JsonObject && el.has("\$b") -> "X'" + Base64.getDecoder().decode(el.get("\$b").asString).joinToString("") { "%02X".format(it) } + "'"
        el.asJsonPrimitive.isBoolean -> if (el.asBoolean) "1" else "0"
        el.asJsonPrimitive.isNumber -> el.asString
        else -> "'" + el.asString.replace("'", "''") + "'"
    }

    /** Full-DB dump as executable SQL (schema from sqlite_master + INSERTs). */
    private fun dumpSql(sdb: SupportSQLiteDatabase): String {
        val sb = StringBuilder()
        sb.append("-- Stun dbweb export ").append(stamp()).append("\nBEGIN TRANSACTION;\n")
        sdb.query(SimpleSQLiteQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")).use { c ->
            val cur = c as? android.database.Cursor ?: return@use
            while (cur.moveToNext()) {
                val (name, ddl) = cur.getString(0) to cur.getString(1)
                if (ddl != null) sb.append(ddl).append(";\n")
                val cols = tableCols(sdb, name).map { it.name }
                val hasRowid = try { sdb.query(SimpleSQLiteQuery("SELECT rowid FROM ${quoteIdent(name)} LIMIT 0")).use { true } } catch (_: Exception) { false }
                val sel = if (hasRowid) "SELECT rowid AS _rowid_, * FROM ${quoteIdent(name)}" else "SELECT * FROM ${quoteIdent(name)}"
                val colList = (if (hasRowid) listOf("rowid") else emptyList()) + cols
                val colSql = colList.joinToString(",") { quoteIdent(it) }
                sdb.query(SimpleSQLiteQuery(sel)).use { rc ->
                    val rcur = rc as? android.database.Cursor ?: return@use
                    val n = rcur.columnCount
                    while (rcur.moveToNext()) {
                        sb.append("INSERT INTO ${quoteIdent(name)} ($colSql) VALUES (")
                        for (i in 0 until n) { if (i > 0) sb.append(","); sb.append(sqlLiteral(cursorCellToJson(rcur, i))) }
                        sb.append(");\n")
                    }
                }
            }
        }
        sb.append("COMMIT;\n")
        return sb.toString()
    }

    private fun dumpCsv(sdb: SupportSQLiteDatabase, table: String): String {
        val cols = tableCols(sdb, table).map { it.name }
        val sb = StringBuilder()
        sb.append(cols.joinToString(",") { csvQuote(it) }).append("\n")
        sdb.query(SimpleSQLiteQuery("SELECT * FROM ${quoteIdent(table)}")).use { c ->
            val cur = c as? android.database.Cursor ?: return@use
            val n = cur.columnCount
            while (cur.moveToNext()) {
                for (i in 0 until n) {
                    if (i > 0) sb.append(",")
                    if (!cur.isNull(i)) sb.append(csvQuote(cur.getString(i)))
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun csvQuote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Replace table contents from a backup JSON (row-level restore; the live Room connection
     *  stays open, so schema must already match — unknown tables/columns are skipped and reported). */
    private fun restoreBackupJson(sdb: SupportSQLiteDatabase, obj: JsonObject): JsonObject {
        val fmt = obj.get("format")?.asString
        require(fmt == BACKUP_FORMAT) { "bad_format" }
        val tables = obj.getAsJsonArray("tables") ?: throw IllegalArgumentException("no_tables")
        val live = listTables(sdb)
        val res = JsonObject()
        res.addProperty("ok", true)
        val restored = JsonArray(); val skipped = JsonArray()
        sdb.setForeignKeyConstraintsEnabled(false)
        try {
            sdb.execSQL("BEGIN IMMEDIATE")
            try {
                for (te in tables) {
                    val to = te as? JsonObject ?: continue
                    val name = to.get("name")?.asString ?: continue
                    if (name !in live) { skipped.add(name); continue }
                    val liveCols = tableCols(sdb, name).map { it.name }.toSet()
                    val bcols = to.getAsJsonArray("columns")?.map { it.asString } ?: continue
                    val keep = bcols.withIndex().filter { (_, c) -> c == "_rowid_" || c in liveCols }
                    if (keep.isEmpty()) { skipped.add(name); continue }
                    val insCols = keep.map { (_, c) -> if (c == "_rowid_") "rowid" else c }
                    val sql = "INSERT INTO ${quoteIdent(name)} (${insCols.joinToString(",") { quoteIdent(it) }}) " +
                        "VALUES (${insCols.joinToString(",") { "?" }})"
                    sdb.execSQL("DELETE FROM ${quoteIdent(name)}")
                    var count = 0L
                    sdb.compileStatement(sql).use { stmt ->
                        for (row in to.getAsJsonArray("rows") ?: JsonArray()) {
                            val ra = row as? JsonArray ?: continue
                            stmt.clearBindings()
                            keep.forEachIndexed { bindIdx, src -> bindJson(stmt, bindIdx + 1, ra.get(src.index)) }
                            stmt.executeUpdateDelete()
                            count++
                        }
                    }
                    val o = JsonObject(); o.addProperty("table", name); o.addProperty("rows", count)
                    restored.add(o)
                }
                sdb.execSQL("COMMIT")
            } catch (e: Exception) {
                try { sdb.execSQL("ROLLBACK") } catch (_: Exception) {}
                throw e
            }
        } finally {
            sdb.setForeignKeyConstraintsEnabled(true)
        }
        res.add("restored", restored); res.add("skipped", skipped)
        return res
    }

    private fun bindJson(stmt: androidx.sqlite.db.SupportSQLiteStatement, i: Int, el: com.google.gson.JsonElement?) {
        when {
            el == null || el.isJsonNull -> stmt.bindNull(i)
            el is JsonObject && el.has("\$b") -> stmt.bindBlob(i, Base64.getDecoder().decode(el.get("\$b").asString))
            el.asJsonPrimitive.isBoolean -> stmt.bindLong(i, if (el.asBoolean) 1L else 0L)
            el.asJsonPrimitive.isNumber -> {
                val d = el.asDouble
                if (d == Math.floor(d) && !d.isInfinite() && !d.isNaN() && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) stmt.bindLong(i, d.toLong())
                else stmt.bindDouble(i, d)
            }
            else -> stmt.bindString(i, el.asString)
        }
    }

    // ── JSON helpers ───────────────────────────────────────────
    private fun errorObj(msg: String): JsonObject { val o = JsonObject(); o.addProperty("ok", false); o.addProperty("error", msg); return o }

    private fun jsonCellToBind(el: com.google.gson.JsonElement?): Any? {
        if (el == null || el.isJsonNull) return null
        return when {
            el.asJsonPrimitive.isBoolean -> if (el.asBoolean) 1L else 0L
            el.asJsonPrimitive.isNumber -> {
                val d = el.asDouble
                if (d == Math.floor(d) && !d.isInfinite() && !d.isNaN() && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) d.toLong() else d
            }
            else -> el.asString
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

// ── SupportSQLiteDatabase extensions ───────────────────────────
private fun SupportSQLiteDatabase.queryToColumnsAndRows(query: SimpleSQLiteQuery, maxRows: Int): Pair<List<String>, JsonArray> {
    val out = JsonArray()
    val columns = ArrayList<String>()
    (query(query) as? android.database.Cursor)?.use { cur ->
        val n = cur.columnCount
        for (i in 0 until n) columns.add(cur.getColumnName(i))
        var count = 0
        while (cur.moveToNext() && count < maxRows) {
            val row = JsonArray()
            for (i in 0 until n) {
                if (cur.isNull(i)) { row.add(com.google.gson.JsonNull.INSTANCE); continue }
                when (cur.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.add(cur.getLong(i))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.add(cur.getDouble(i))
                    android.database.Cursor.FIELD_TYPE_BLOB -> {
                        val blob = cur.getBlob(i)
                        row.add("<BLOB ${blob.size} bytes>")
                    }
                    else -> row.add(cur.getString(i))
                }
            }
            out.add(row); count++
        }
        if (cur.count > maxRows) { /* truncation signalled by caller via maxRows */ }
    }
    return columns to out
}

private fun SupportSQLiteDatabase.insertAndReturnRowId(sql: String, args: List<Any?>): Long {
    return compileStatement(sql).use { stmt ->
        bindArgs(stmt, args)
        stmt.executeInsert()
    }
}

private fun SupportSQLiteDatabase.executeUpdate(sql: String, args: List<Any?>): Int {
    var affected = -1
    compileStatement(sql).use { stmt ->
        bindArgs(stmt, args)
        affected = stmt.executeUpdateDelete()
    }
    return affected
}

private fun bindArgs(stmt: androidx.sqlite.db.SupportSQLiteStatement, args: List<Any?>) {
    for ((idx, v) in args.withIndex()) {
        val i = idx + 1
        when (v) {
            null -> stmt.bindNull(i)
            is Long -> stmt.bindLong(i, v)
            is Int -> stmt.bindLong(i, v.toLong())
            is Double -> stmt.bindDouble(i, v)
            is Float -> stmt.bindDouble(i, v.toDouble())
            is Boolean -> stmt.bindLong(i, if (v) 1 else 0)
            else -> stmt.bindString(i, v.toString())
        }
    }
}

/**
 * Top-level ktor module entry for [DbWebServer].
 *
 * Declared at file scope (not as a member of the `object`) and invoked from a plain lambda at the
 * `embeddedServer(...)` call site. This keeps it a regular `Function`-style target so the call site
 * can use a non-reflective lambda (`module is KFunction` is false) — ktor then skips the
 * kotlin-reflect `methodName` path that crashes in release (R8 strips the file-facade @Metadata,
 * so a `KFunction` reference resolves to "no members found" and DbWebServer.start() throws).
 */
internal fun Application.dbWebModule() {
    val s = DbWebServer
    routing {
        // Static shell assets are NOT gated; all data behind /api requires a session.
        get("/") { s.run { call.respondAsset(s.appCtx, "dbweb/index.html", ContentType.Text.Html) } }
        get("/app.js") { s.run { call.respondAsset(s.appCtx, "dbweb/app.js", ContentType.parse("application/javascript")) } }
        get("/style.css") { s.run { call.respondAsset(s.appCtx, "dbweb/style.css", ContentType.Text.CSS) } }

        post("/api/login") { s.handleLogin(s.appCtx, call) }
        post("/api/logout") { s.handleLogout(call) }
        get("/api/me") { s.handleMe(s.appCtx, call) }

        // gated data API
        get("/api/tables") { s.requireSession(call) { s.handleTables(s.appCtx, call) } }
        get("/api/schema") { s.requireSession(call) { s.handleSchema(s.appCtx, call) } }
        get("/api/rows") { s.requireSession(call) { s.handleRows(s.appCtx, call) } }
        post("/api/row/insert") { s.requireSession(call) { s.handleRowWrite(s.appCtx, call, "insert") } }
        post("/api/row/update") { s.requireSession(call) { s.handleRowWrite(s.appCtx, call, "update") } }
        post("/api/row/delete") { s.requireSession(call) { s.handleRowDelete(s.appCtx, call) } }
        post("/api/sql") { s.requireSession(call) { s.handleSql(s.appCtx, call) } }

        // export / import / backups
        get("/api/export") { s.requireSession(call) { s.handleExport(s.appCtx, call) } }
        post("/api/import") { s.requireSession(call) { s.handleImport(s.appCtx, call) } }
        post("/api/backup/create") { s.requireSession(call) { s.handleBackupCreate(s.appCtx, call) } }
        get("/api/backup/list") { s.requireSession(call) { s.handleBackupList(s.appCtx, call) } }
        get("/api/backup/download") { s.requireSession(call) { s.handleBackupDownload(s.appCtx, call) } }
        post("/api/backup/restore") { s.requireSession(call) { s.handleBackupRestore(s.appCtx, call) } }
        post("/api/backup/delete") { s.requireSession(call) { s.handleBackupDelete(s.appCtx, call) } }
    }
}
