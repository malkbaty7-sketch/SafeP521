package com.example.streaming

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.security.SecurityAction
import com.example.security.SecurityManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

class WifiShareServer(
    private val context: Context,
    private val repository: MediaRepository,
    private val securityManager: SecurityManager,
    private val port: Int = DEFAULT_PORT
) {
    @Volatile
    private var server: LocalHttpServer? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var pairingCode: String? = null
        private set

    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val pairingAttempts = ConcurrentHashMap<String, PairingAttempt>()
    private val random = SecureRandom()
    private val uploadSemaphore = Semaphore(1)

    @Synchronized
    fun start(onStatusChange: (Boolean, String) -> Unit) {
        if (isRunning) {
            onStatusChange(true, "http://${getIpAddress()}:$port")
            return
        }
        try {
            val ip = getIpAddress()
            require(ip != "127.0.0.1" && ip != "0.0.0.0") {
                "اتصل بشبكة محلية قبل تشغيل المشاركة"
            }
            val nextServer = LocalHttpServer(ip)
            nextServer.start(SOCKET_READ_TIMEOUT_MS, true)
            server = nextServer
            pairingCode = generatePairingCode()
            sessions.clear()
            pairingAttempts.clear()
            isRunning = true
            onStatusChange(true, "http://$ip:$port")
        } catch (error: Exception) {
            server?.stop()
            server = null
            pairingCode = null
            isRunning = false
            onStatusChange(false, error.localizedMessage ?: "فشل بدء خادم المشاركة")
        }
    }

    @Synchronized
    fun stop(onStatusChange: (Boolean, String) -> Unit) {
        try {
            server?.stop()
            server = null
            sessions.clear()
            pairingAttempts.clear()
            pairingCode = null
            isRunning = false
            onStatusChange(false, "تم إيقاف الخادم وإبطال جلسات المشاركة")
        } catch (error: Exception) {
            onStatusChange(false, error.localizedMessage ?: "خطأ أثناء إيقاف الخادم")
        }
    }

    fun getIpAddress(): String = findLocalIpv4Address() ?: "127.0.0.1"

    private inner class LocalHttpServer(bindAddress: String) : NanoHTTPD(bindAddress, port) {
        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = handleRequest(session)
    }

    private fun handleRequest(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            when {
                request.uri == "/pair" -> handlePair(request)
                request.uri == "/" -> handleHome(request)
                request.uri == "/media" -> handleMedia(request)
                request.uri == "/upload" -> handleUpload(request)
                else -> htmlResponse(NanoHTTPD.Response.Status.NOT_FOUND, page("غير موجود", "المسار المطلوب غير متاح."))
            }
        } catch (_: Exception) {
            htmlResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                page("تعذر إكمال الطلب", "حدث خطأ أثناء معالجة الطلب. أوقف المشاركة ثم حاول مجدداً.")
            )
        }
    }

    private fun handleHome(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (request.method != NanoHTTPD.Method.GET) {
            return htmlResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, page("طريقة غير مدعومة", "استخدم طلب GET."))
        }
        if (!isAuthorized(request)) return htmlResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, pairingPage())
        if (!canShare()) return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("المشاركة موقوفة", "افتح التطبيق وأوقف القفل أو القيود التي تمنع المشاركة."))

        val mediaList = runBlocking { repository.allMedia.firstOrNull() }.orEmpty()
        val itemsHtml = if (mediaList.isEmpty()) {
            "<p class=\"empty\">لا توجد ملفات وسائط حالياً.</p>"
        } else {
            mediaList.joinToString(separator = "") { item ->
                val isVideo = item.mimeType?.startsWith("video/", ignoreCase = true) == true ||
                    item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true) || item.name.endsWith(".webm", true)
                val badge = if (isVideo) "فيديو" else "صوت"
                val sizeMb = "%.1f".format(item.sizeBytes.coerceAtLeast(0) / (1024.0 * 1024.0))
                if (runBlocking { securityManager.isMediaProtected(item.id) }) {
                    """<article class="card"><div><span class="badge">محتوى محمي</span><h2>عنصر في الخزنة</h2><p>لن تتم مشاركة هذا الملف عبر الشبكة.</p></div></article>"""
                } else {
                    """
                    <article class="card">
                        <div class="info"><span class="badge">$badge</span><h2>${escapeHtml(item.name)}</h2><p>الحجم: $sizeMb MB</p></div>
                        <div class="actions">
                            <a class="button play" href="/media?id=${item.id}&amp;action=play">تشغيل</a>
                            <a class="button" href="/media?id=${item.id}&amp;action=download">تنزيل</a>
                        </div>
                    </article>
                    """.trimIndent()
                }
            }
        }
        return htmlResponse(NanoHTTPD.Response.Status.OK, homePage(itemsHtml, mediaList.size))
    }

    private fun handlePair(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (request.method != NanoHTTPD.Method.POST) {
            return htmlResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, page("طريقة غير مدعومة", "أرسل رمز الاقتران باستخدام POST."))
        }
        if (!isSameOriginIfProvided(request)) {
            return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("طلب مرفوض", "تعذر التحقق من مصدر الطلب."))
        }

        val formContentType = request.headers.headerValue("content-type").orEmpty()
        val formLength = request.headers.headerValue("content-length")?.toLongOrNull()
        if (!formContentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true) ||
            formLength == null || formLength !in 1L..256L
        ) {
            return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("طلب غير صالح", "صيغة طلب الاقتران غير مدعومة."))
        }

        val clientIp = request.remoteIpAddress ?: "unknown"
        val waitMs = pairingBlockedFor(clientIp)
        if (waitMs > 0) {
            return htmlResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                page("محاولات كثيرة", "انتظر ${(waitMs + 999) / 1000} ثانية قبل إعادة المحاولة.")
            )
        }

        val formData = HashMap<String, String>()
        request.parseBody(formData)
        val submittedCode = request.parms["code"].orEmpty()
        val expectedCode = pairingCode
        if (!isRunning || expectedCode == null || !constantTimeEquals(submittedCode, expectedCode)) {
            recordFailedPairing(clientIp)
            return htmlResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, pairingPage("رمز الاقتران غير صحيح."))
        }
        if (!canShare()) {
            return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("المشاركة موقوفة", "افتح التطبيق وتحقق من إعدادات الأمان."))
        }

        pruneExpiredSessions()
        if (sessions.size >= MAX_ACTIVE_SESSIONS) {
            return htmlResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, page("وصلت للحد", "أوقف جلسة مشاركة أخرى أو أعد تشغيل الخادم."))
        }

        pairingAttempts.remove(clientIp)
        val token = createSession(clientIp)
        val response = htmlResponse(
            NanoHTTPD.Response.Status.OK,
            page("تم الاقتران", "تم توثيق هذا الجهاز مؤقتاً. <a href=\"/\">افتح مكتبة الوسائط</a>.")
        )
        response.addHeader(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_IDLE_TIMEOUT_MS / 1000}"
        )
        return response
    }

    private fun handleMedia(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (request.method != NanoHTTPD.Method.GET && request.method != NanoHTTPD.Method.HEAD) {
            return htmlResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, page("طريقة غير مدعومة", "استخدم GET أو HEAD."))
        }
        if (!isAuthorized(request)) return htmlResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, page("يلزم الاقتران", "ارجع إلى الصفحة الرئيسية وأدخل رمز الاقتران."))
        if (!canShare()) return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("المشاركة موقوفة", "تحقق من حالة القفل والرقابة الأبوية في التطبيق."))

        val id = request.parms["id"]?.toIntOrNull()
            ?: return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("طلب غير صالح", "معرّف الوسيط غير صالح."))
        if (runBlocking { securityManager.isMediaProtected(id) }) {
            return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("محتوى محمي", "لا تتم مشاركة عناصر الخزنة عبر Wi-Fi."))
        }
        val item = runBlocking { repository.getMediaById(id).firstOrNull() }
            ?: return htmlResponse(NanoHTTPD.Response.Status.NOT_FOUND, page("غير موجود", "لم يتم العثور على الوسيط."))

        return when (request.parms["action"] ?: "play") {
            "play" -> htmlResponse(NanoHTTPD.Response.Status.OK, playerPage(item))
            "stream", "download" -> streamMedia(request, item)
            else -> htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("طلب غير صالح", "الإجراء المطلوب غير معروف."))
        }
    }

    private fun streamMedia(request: NanoHTTPD.IHTTPSession, item: MediaItem): NanoHTTPD.Response {
        val resource = openMedia(item)
            ?: return htmlResponse(NanoHTTPD.Response.Status.NOT_FOUND, page("الملف غير متاح", "تعذر فتح مصدر الوسيط."))
        val rangeHeader = request.headers.headerValue("range")
        val range = if (resource.supportsRanges && rangeHeader != null) {
            parseByteRange(rangeHeader, resource.length)
                ?: run {
                    resource.input.close()
                    return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("نطاق غير صالح", "صيغة طلب نطاق الوسائط غير مدعومة."))
                }
        } else null

        val input: InputStream
        val responseLength: Long
        val status: NanoHTTPD.Response.IStatus
        if (range != null) {
            try {
                skipFully(resource.input, range.start)
            } catch (_: IOException) {
                resource.input.close()
                return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("نطاق غير صالح", "تعذر قراءة موضع الوسيط المطلوب."))
            }
            input = LimitedInputStream(resource.input, range.end - range.start + 1)
            responseLength = range.end - range.start + 1
            status = NanoHTTPD.Response.Status.PARTIAL_CONTENT
        } else {
            input = resource.input
            responseLength = resource.length
            status = NanoHTTPD.Response.Status.OK
        }

        val response = if (responseLength > 0L) {
            NanoHTTPD.newFixedLengthResponse(status, resource.mimeType, input, responseLength)
        } else {
            NanoHTTPD.newChunkedResponse(status, resource.mimeType, input)
        }
        response.addHeader("Cache-Control", "private, no-store")
        if (resource.supportsRanges) response.addHeader("Accept-Ranges", "bytes")
        if (range != null) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/${resource.length}")
        val disposition = if (request.parms["action"] == "download") "attachment" else "inline"
        val safeName = safeFileName(item.name)
        val encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8.name()).replace("+", "%20")
        response.addHeader("Content-Disposition", "$disposition; filename=\"$safeName\"; filename*=UTF-8''$encodedName")
        addSecurityHeaders(response)
        return response
    }

    private fun handleUpload(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (request.method != NanoHTTPD.Method.POST) {
            return htmlResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, page("طريقة غير مدعومة", "استخدم POST لرفع ملف."))
        }
        if (!isAuthorized(request)) return htmlResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, page("يلزم الاقتران", "ارجع إلى الصفحة الرئيسية وأدخل رمز الاقتران."))
        if (!isSameOriginRequired(request)) return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("طلب مرفوض", "تعذر التحقق من مصدر الطلب."))
        if (!canShare() || !canAddSource()) {
            return htmlResponse(NanoHTTPD.Response.Status.FORBIDDEN, page("الرفع موقوف", "الرقابة الأبوية أو قفل التطبيق يمنعان هذا الإجراء."))
        }

        val contentType = request.headers.headerValue("content-type").orEmpty()
        if (!contentType.equals("application/octet-stream", ignoreCase = true)) {
            return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("صيغة غير مدعومة", "أرسل الملف بصيغة application/octet-stream."))
        }
        if (request.headers.headerValue("transfer-encoding")?.contains("chunked", ignoreCase = true) == true) {
            return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("طلب غير مدعوم", "يجب إرسال طول الملف صراحةً."))
        }
        val contentLength = request.headers.headerValue("content-length")?.toLongOrNull()
            ?: return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("حجم غير معروف", "تعذر قراءة حجم الملف."))
        if (contentLength <= 0L || contentLength > MAX_UPLOAD_BYTES) {
            return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("حجم غير مقبول", "الحد الأقصى للرفع هو ${MAX_UPLOAD_LABEL}."))
        }
        if (!uploadSemaphore.tryAcquire()) {
            return htmlResponse(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, page("الرفع مشغول", "انتظر حتى ينتهي الرفع الجاري."))
        }

        var targetFile: File? = null
        var uploadComplete = false
        try {
            val uploadDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Downloads"),
                "SafePlayer_Received"
            )
            if (!uploadDir.exists() && !uploadDir.mkdirs()) throw IOException("Could not create upload directory")
            val name = safeFileName(request.parms["name"].orEmpty())
            val target = File(uploadDir, "${System.currentTimeMillis()}_$name")
            targetFile = target
            if (target.canonicalFile.parentFile != uploadDir.canonicalFile) throw IOException("Invalid upload path")

            var received = 0L
            val input = request.inputStream
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (received < contentLength) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), contentLength - received).toInt())
                    if (count < 0) throw IOException("Upload ended before Content-Length")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    received += count
                }
            }
            if (received != contentLength) throw IOException("Upload length mismatch")
            uploadComplete = true
            return htmlResponse(
                NanoHTTPD.Response.Status.OK,
                page("اكتمل الرفع", "تم حفظ الملف في مجلد SafePlayer_Received داخل مساحة التطبيق. يمكنك إيقاف الخادم الآن.")
            )
        } catch (_: Exception) {
            return htmlResponse(NanoHTTPD.Response.Status.BAD_REQUEST, page("فشل الرفع", "تعذر حفظ الملف أو تجاوز الحد المسموح."))
        } finally {
            if (!uploadComplete) targetFile?.delete()
            uploadSemaphore.release()
        }
    }

    private fun openMedia(item: MediaItem): MediaResource? {
        val uri = runCatching { Uri.parse(item.path) }.getOrNull()
        val mimeType = item.mimeType?.takeIf { it.contains('/') && !it.contains('\r') && !it.contains('\n') }
            ?: uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            ?: "application/octet-stream"
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            return MediaResource(stream, item.sizeBytes.coerceAtLeast(0L), mimeType, supportsRanges = false)
        }

        val file = if (uri?.scheme.equals("file", ignoreCase = true)) {
            uri?.path?.let(::File) ?: return null
        } else {
            File(item.path)
        }
        if (!file.isFile || !file.canRead()) return null
        val length = file.length()
        val stream = FileInputStream(file)
        return MediaResource(stream, length, mimeType, supportsRanges = true)
    }

    private fun handlePairingPage(message: String? = null): String {
        val error = message?.let { "<p class=\"error\">${escapeHtml(it)}</p>" }.orEmpty()
        return page(
            "اقتران جهاز",
            """
            <p>أدخل رمز الاقتران الظاهر داخل SafePlayer. الرمز مطلوب لكل جهاز جديد، والجلسة تنتهي تلقائياً بعد 15 دقيقة من عدم النشاط.</p>
            $error
            <form method="post" action="/pair" autocomplete="off">
              <label for="code">رمز الاقتران</label>
              <input id="code" name="code" type="password" inputmode="numeric" pattern="[0-9]{8}" maxlength="8" required autofocus>
              <button type="submit">اقتران مؤقت</button>
            </form>
            """.trimIndent()
        )
    }

    private fun pairingPage(message: String? = null): String = handlePairingPage(message)

    private fun homePage(itemsHtml: String, itemCount: Int): String = page(
        "SafePlayer Wi-Fi",
        """
        <header><h1>SafePlayer Wi-Fi</h1><p>جلسة محلية مؤقتة · $itemCount عنصر</p></header>
        <section class="upload">
          <h2>رفع ملف إلى الهاتف</h2>
          <p>يُحفظ الملف في مساحة SafePlayer الخاصة. الحد الأقصى: $MAX_UPLOAD_LABEL.</p>
          <form id="upload-form">
            <input id="upload-file" type="file" required>
            <button type="submit">رفع الملف</button>
          </form>
          <p id="upload-status" role="status"></p>
        </section>
        <section><h2>الوسائط المتاحة للمشاركة</h2><div class="list">$itemsHtml</div></section>
        <script>
          document.getElementById('upload-form').addEventListener('submit', async function (event) {
            event.preventDefault();
            const file = document.getElementById('upload-file').files[0];
            const status = document.getElementById('upload-status');
            if (!file) return;
            status.textContent = 'جارٍ الرفع…';
            try {
              const response = await fetch('/upload?name=' + encodeURIComponent(file.name), {
                method: 'POST',
                headers: { 'Content-Type': 'application/octet-stream' },
                body: file,
                credentials: 'same-origin'
              });
              const result = await response.text();
              status.textContent = response.ok ? 'اكتمل الرفع إلى مجلد SafePlayer_Received.' : 'فشل الرفع (' + response.status + ').';
            } catch (_) { status.textContent = 'تعذر الاتصال بالخادم.'; }
          });
        </script>
        """.trimIndent()
    )

    private fun playerPage(item: MediaItem): String {
        val isVideo = item.mimeType?.startsWith("video/", ignoreCase = true) == true ||
            item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true) || item.name.endsWith(".webm", true)
        val mediaTag = if (isVideo) {
            "<video controls autoplay playsinline preload=\"metadata\" src=\"/media?id=${item.id}&amp;action=stream\"></video>"
        } else {
            "<audio controls autoplay preload=\"metadata\" src=\"/media?id=${item.id}&amp;action=stream\"></audio>"
        }
        return page(escapeHtml(item.name), "<a href=\"/\">العودة إلى القائمة</a><h1>${escapeHtml(item.name)}</h1>$mediaTag")
    }

    private fun page(title: String, body: String): String = """
        <!doctype html><html lang="ar" dir="rtl"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="no-referrer"><title>${escapeHtml(title)}</title>
        <style>
          :root{color-scheme:dark;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#121316;color:#f0f2f5}
          *{box-sizing:border-box}body{margin:0;padding:24px;line-height:1.6}.wrap{max-width:850px;margin:0 auto}
          h1{color:#7ba9ff;margin:.2em 0}h2{font-size:1.1rem}.card,.upload,form{background:#1e2025;border:1px solid #30343e;border-radius:12px;padding:16px;margin:14px 0}
          .card{display:flex;justify-content:space-between;align-items:center;gap:16px}.info{min-width:0}.card h2{overflow-wrap:anywhere}.badge{color:#9dc0ff;font-size:.8rem}
          .actions{display:flex;gap:8px;flex-shrink:0}.button,button{border:0;border-radius:8px;background:#37474f;color:#fff;padding:10px 14px;text-decoration:none;font:inherit;cursor:pointer}
          .button.play{background:#246b39}input{display:block;width:100%;margin:10px 0;padding:12px;border-radius:8px;border:1px solid #687080;background:#111318;color:#fff}
          .error{color:#ff8d8d}.empty{color:#aaa;text-align:center}video,audio{display:block;width:100%;max-height:80vh;margin:20px auto 0}
          @media(max-width:560px){body{padding:14px}.card{align-items:flex-start;flex-direction:column}.actions{width:100%}.actions .button{flex:1;text-align:center}}
        </style></head><body><main class="wrap">$body</main></body></html>
    """.trimIndent()

    private fun isAuthorized(request: NanoHTTPD.IHTTPSession): Boolean {
        val cookieHeader = request.headers.headerValue("cookie") ?: return false
        val token = cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
            ?: return false
        val session = sessions[token] ?: return false
        val ip = request.remoteIpAddress ?: return false
        val now = System.currentTimeMillis()
        if (session.ipAddress != ip || now - session.lastActivity.get() > SESSION_IDLE_TIMEOUT_MS) {
            sessions.remove(token, session)
            return false
        }
        session.lastActivity.set(now)
        return true
    }

    private fun createSession(ipAddress: String): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        sessions[token] = ClientSession(ipAddress, AtomicLong(System.currentTimeMillis()))
        return token
    }

    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.lastActivity.get() > SESSION_IDLE_TIMEOUT_MS }
    }

    private fun pairingBlockedFor(ipAddress: String): Long {
        val attempt = pairingAttempts[ipAddress] ?: return 0L
        synchronized(attempt) {
            val remaining = attempt.blockedUntil - System.currentTimeMillis()
            if (remaining > 0) return remaining
            if (attempt.blockedUntil != 0L) {
                attempt.failures = 0
                attempt.blockedUntil = 0L
            }
            return 0L
        }
    }

    private fun recordFailedPairing(ipAddress: String) {
        val attempt = pairingAttempts.getOrPut(ipAddress) { PairingAttempt() }
        synchronized(attempt) {
            attempt.failures++
            if (attempt.failures >= MAX_PAIRING_FAILURES) {
                attempt.failures = 0
                attempt.blockedUntil = System.currentTimeMillis() + PAIRING_LOCKOUT_MS
            }
        }
        if (pairingAttempts.size > 128) {
            pairingAttempts.entries.removeIf { it.value.blockedUntil < System.currentTimeMillis() && it.value.failures == 0 }
        }
    }

    private fun generatePairingCode(): String = (10_000_000 + random.nextInt(90_000_000)).toString()

    private fun constantTimeEquals(candidate: String, expected: String): Boolean {
        if (candidate.length != expected.length) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII)
        )
    }

    private fun canShare(): Boolean = runBlocking { securityManager.canPerformAction(SecurityAction.SHARE) }

    private fun canAddSource(): Boolean = runBlocking { securityManager.canPerformAction(SecurityAction.ADD_SOURCE) }

    private fun isSameOriginIfProvided(request: NanoHTTPD.IHTTPSession): Boolean {
        val origin = request.headers.headerValue("origin") ?: return true
        val host = request.headers.headerValue("host") ?: return false
        return origin.equals("http://$host", ignoreCase = true)
    }

    private fun isSameOriginRequired(request: NanoHTTPD.IHTTPSession): Boolean =
        request.headers.headerValue("origin") != null && isSameOriginIfProvided(request)

    private fun htmlResponse(status: NanoHTTPD.Response.IStatus, html: String): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=utf-8", html)
        addSecurityHeaders(response)
        return response
    }

    private fun addSecurityHeaders(response: NanoHTTPD.Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; media-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        )
    }

    private fun parseByteRange(header: String, totalLength: Long): ByteRange? {
        if (totalLength <= 0L || !header.startsWith("bytes=", ignoreCase = true)) return null
        val parts = header.substringAfter('=').split('-')
        if (parts.size != 2 || header.contains(',')) return null
        val start: Long
        val end: Long
        if (parts[0].isBlank()) {
            val suffixLength = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            start = (totalLength - suffixLength).coerceAtLeast(0L)
            end = totalLength - 1
        } else {
            start = parts[0].toLongOrNull() ?: return null
            end = if (parts[1].isBlank()) totalLength - 1 else parts[1].toLongOrNull() ?: return null
        }
        if (start < 0L || start >= totalLength || end < start) return null
        return ByteRange(start, end.coerceAtMost(totalLength - 1))
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() == -1) {
                throw IOException("Could not seek to requested byte range")
            } else {
                remaining--
            }
        }
    }

    private fun safeFileName(input: String): String {
        val leaf = input.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .trim()
            .trim('.')
            .take(120)
        return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "uploaded_media.bin"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private data class ClientSession(val ipAddress: String, val lastActivity: AtomicLong)
    private data class PairingAttempt(var failures: Int = 0, var blockedUntil: Long = 0L)
    private data class ByteRange(val start: Long, val end: Long)
    private data class MediaResource(
        val input: InputStream,
        val length: Long,
        val mimeType: String,
        val supportsRanges: Boolean
    )

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }
    }

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val SOCKET_READ_TIMEOUT_MS = 10_000
        private const val SESSION_COOKIE = "SAFEPLAYER_SESSION"
        private const val SESSION_IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        private const val MAX_ACTIVE_SESSIONS = 3
        private const val MAX_PAIRING_FAILURES = 5
        private const val PAIRING_LOCKOUT_MS = 60_000L
        private const val MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L
        private const val MAX_UPLOAD_LABEL = "1 GiB"

        private fun findLocalIpv4Address(): String? {
            return try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
                    .sortedBy { networkInterface -> if (networkInterface.name.startsWith("wlan", true)) 0 else 1 }
                for (networkInterface in interfaces) {
                    for (address in Collections.list(networkInterface.inetAddresses)) {
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            return address.hostAddress
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
