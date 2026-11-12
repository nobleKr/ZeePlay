package com.shilapi.xcertplay

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Optional car media-center bridge, owned by one connected CarPlay controller. */
internal class ZeekrMediaSource(
    private val context: Context,
    private val send: (Int) -> Boolean,
    workerHandler: Handler? = null,
) : VehicleMediaSource {
    private data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val positionAt: Long = SystemClock.elapsedRealtime())
    private data class Publication(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Uri? = null,
        val positionAt: Long = SystemClock.elapsedRealtime())
    @Volatile private var snapshot = Snapshot()
    @Volatile private var closed = false
    private val publishQueued = AtomicBoolean()
    private val workerDelegate = lazy { workerHandler ?: Handler(thread.looper) }
    private val worker by workerDelegate
    @Volatile private var bound = false // changed on main; read when metadata arrives
    private val main = Handler(android.os.Looper.getMainLooper())
    @Volatile private var directBound = false // changed on main; read when metadata arrives
    private var directAttempted = false // at most once per CarPlay session
    private var router: IBinder? = null // worker only
    private var routerDescriptor = ZeekrMediaProtocol.CORE
    private var core: IBinder? = null // worker only, including the fields below
    private var serviceListener: IBinder? = null
    private var media: IBinder? = null
    private var token: IBinder? = null
    @Volatile private var epoch = 0
    @Volatile private var registrationOwner: Any? = null
    private var registered = false
    private var initAttempts = 0
    private var initOutstanding = false
    private var initRetry: Runnable? = null
    private var initDeadline: Runnable? = null
    private var previous: Snapshot? = null
    private var cover: Bitmap? = null
    @Volatile private var publication = Publication()
    private var coverUri: Uri? = null
    private val covers = ArrayDeque<Uri>()
    private val coverFiles = ArrayDeque<File>()
    private val autoSelect = ZeekrInteractionSettings.autoSelect(context)
    private val controlsEnabled = ZeekrInteractionSettings.controlsEnabled(context)
    private var yielded = false
    private var lastFocusState: String? = null
    private val folder = File(context.cacheDir, "zeekr-artwork/${UUID.randomUUID()}")
    /**
     * Hold the media-center focus on a steady 1 s tick, independent of the CarPlay now-playing
     * "playing" flag (which can stay false while audio streams). Mirrors the working media bridge,
     * which holds focus from the start and re-acquires continuously; [ensureFocusHeld] no-ops when we
     * already own the source or the car revoked it ([yielded]).
     */
    private val focusTick = object : Runnable {
        override fun run() {
            if (closed || token == null || media == null) return
            safely("focus-hold") { ensureFocusHeld() }
            if (!closed && token != null && media != null) worker.postDelayed(this, 1000L)
        }
    }
    private val progressTick = object : Runnable {
        override fun run() {
            if (closed || token == null || media == null || !snapshot.info.playing) return
            safely("progress") {
                val current = snapshot
                mediaCommand(11) { it.writeLong(position(current.info, current.positionAt)) }
                ensureFocusHeld()
                worker.postDelayed(this, 1000L)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            note("CoreService connected")
            worker.post {
                if (!closed && !(routerDescriptor == ZeekrMediaProtocol.SUPPORT && router != null)) initialize(service)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            note("CoreService disconnected")
            worker.post { if (!closed && routerDescriptor == ZeekrMediaProtocol.CORE) invalidate() }
        }
        override fun onBindingDied(name: ComponentName) {
            stopBinding()
            if (!closed && !directBound) start()
        }
        override fun onNullBinding(name: ComponentName) {
            note("CoreService returned null binding"); stopBinding(); startDirect()
        }
    }

    private val directConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            worker.post {
                if (closed) return@post
                if (routerDescriptor == ZeekrMediaProtocol.CORE && registered) {
                    note("CoreService recovered before fallback connected")
                    main.post { stopDirectBinding() }
                    return@post
                }
                safely("direct media interface") {
                    if (service.interfaceDescriptor != ZeekrMediaProtocol.SUPPORT) {
                        note("direct media interface unsupported")
                        main.post { stopDirectBinding() }
                        return@safely
                    }
                    // Retire the CoreService generation before accepting direct callbacks.
                    removeCoreListener()
                    invalidate()
                    router = service
                    routerDescriptor = ZeekrMediaProtocol.SUPPORT
                    note("direct media support connected")
                    register(epoch)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            note("direct media service disconnected")
            worker.post { if (!closed && routerDescriptor == ZeekrMediaProtocol.SUPPORT) invalidate() }
        }
        override fun onBindingDied(name: ComponentName) {
            note("direct media binding died; reconnect CarPlay to retry")
            stopDirectBinding()
            worker.post { if (!closed && routerDescriptor == ZeekrMediaProtocol.SUPPORT) invalidate() }
        }
        override fun onNullBinding(name: ComponentName) {
            note("direct media returned null binding")
            stopDirectBinding()
        }
    }

    private fun startDirect() {
        main.post {
            if (closed || directAttempted) return@post
            directAttempted = true
            val intent = Intent("ecarx.xsf.ZEEKR_MEDIA_CENTER_SERVICE").setComponent(DIRECT_COMPONENT)
            directBound = runCatching {
                context.packageManager.resolveService(intent, 0) != null &&
                    context.bindService(intent, directConnection, Context.BIND_AUTO_CREATE)
            }.onFailure { note("direct media bind failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
            note(if (directBound) "binding direct media support" else "direct media support unavailable")
        }
    }

    private fun stopDirectBinding() {
        if (directBound) { runCatching { context.unbindService(directConnection) }; directBound = false }
    }

    private fun removeCoreListener() {
        val service = core ?: return
        val listener = serviceListener ?: return
        safely("remove listener") {
            ZeekrMediaProtocol.command(service, ZeekrMediaProtocol.CORE, 3) { it.writeStrongBinder(listener) }
        }
    }

    fun start() {
        if (closed || bound || directBound) return
        val intent = Intent().setComponent(COMPONENT)
        bound = runCatching {
            context.packageManager.resolveService(intent, 0) != null &&
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
            .onFailure { note("bind failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
        if (bound) note("binding CoreService") else startDirect()
    }

    override fun update(info: CarPlayNowPlaying, artwork: Bitmap?) {
        if (closed) return
        val old = snapshot
        val anchor = if (old.info.elapsedMillis != info.elapsedMillis || old.info.playing != info.playing)
            SystemClock.elapsedRealtime() else old.positionAt
        if (old.info.playing != info.playing) note("nowplaying playing=${info.playing}")
        snapshot = Snapshot(info, artwork, anchor)
        if (bound || directBound) queuePublish()
    }

    override fun close() {
        closed = true // Old Binder callbacks stop forwarding controls immediately.
        stopBinding()
        stopDirectBinding()
        if (!workerDelegate.isInitialized()) return
        worker.post {
            val oldToken = token
            val oldMedia = media
            val oldCore = core
            val oldListener = serviceListener
            invalidate()
            if (oldMedia != null && oldToken != null) safely("clear playback") {
                ZeekrMediaProtocol.command(oldMedia, ZeekrMediaProtocol.MEDIA, 7) {
                    it.writeStrongBinder(oldToken); it.writeStrongBinder(playbackInfo)
                }
            }
            if (oldToken != null && oldMedia != null) safely("unregister") {
                ZeekrMediaProtocol.command(oldMedia, ZeekrMediaProtocol.MEDIA, 5) { it.writeStrongBinder(oldToken) }
            }
            if (oldCore != null && oldListener != null) safely("remove listener") {
                ZeekrMediaProtocol.command(oldCore, ZeekrMediaProtocol.CORE, 3) { it.writeStrongBinder(oldListener) }
            }
            covers.forEach { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            folder.deleteRecursively()
        }
    }

    override fun deactivate() { closed = true }

    override fun select(source: Int, display: Int): Boolean = source == ZeekrMediaProtocol.SOURCE &&
        display == 0 && control(epoch, CarPlayMediaButton.PLAY)

    private fun stopBinding() {
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }

    private fun invalidate() {
        worker.removeCallbacks(progressTick)
        worker.removeCallbacks(focusTick)
        cancelInitWait()
        initAttempts = 0
        initOutstanding = false
        epoch++
        registered = false
        registrationOwner = null
        router = null
        core = null
        serviceListener = null
        media = null
        token = null
        previous = null
    }

    private fun initialize(service: IBinder) = safely("initialize") {
        invalidate()
        core = service
        router = service
        routerDescriptor = ZeekrMediaProtocol.CORE
        val generation = epoch
        val listener = ZeekrCallbackBinder(ZeekrMediaProtocol.SERVICE_CALLBACK) { code, data, reply ->
            when (code) {
                1 -> {
                    val name = data.readString()
                    worker.post {
                        if (valid(generation) && name == "mediacenter") {
                            cancelInitWait()
                            initOutstanding = false
                            initAttempts = 0
                            note("media service ready")
                            register(generation)
                        }
                    }
                }
                2 -> {
                    val name = data.readString()
                    val reason = data.readInt()
                    worker.post {
                        if (valid(generation) && name == "mediacenter") {
                            registrationOwner = null
                            registered = false; token = null; media = null; previous = null
                            worker.removeCallbacks(progressTick)
                            worker.removeCallbacks(focusTick)
                            initDeadline?.let { worker.removeCallbacks(it) }
                            initDeadline = null
                            initOutstanding = false
                            note("media service disconnected reason=$reason")
                            if (reason == 305) startDirect()
                            else if (reason in setOf(304, 312, 317)) retryInit(generation)
                        }
                    }
                }
                3 -> {
                    val names = data.createStringArrayList().orEmpty()
                    worker.post {
                        if (valid(generation)) note("available services count=${names.size} media=${"mediacenter" in names}")
                        if (valid(generation) && "mediacenter" in names) initMedia(service)
                    }
                }
                else -> return@ZeekrCallbackBinder false
            }
            reply?.writeNoException()
            true
        }
        serviceListener = listener
        ZeekrMediaProtocol.command(service, ZeekrMediaProtocol.CORE, 2) { it.writeStrongBinder(listener) }
        initMedia(service)
    }

    private fun initMedia(service: IBinder) = safely("init mediacenter") {
        if (closed || registered || initOutstanding || core !== service) return@safely
        if (initAttempts >= 3) { note("media init retries exhausted"); startDirect(); return@safely }
        initOutstanding = true
        initAttempts++
        note("media init attempt=$initAttempts")
        val generation = epoch
        val deadline = Runnable {
            if (valid(generation) && initOutstanding) {
                initOutstanding = false
                note("media init readiness timed out")
                retryInit(generation)
            }
        }
        initDeadline = deadline
        worker.postDelayed(deadline, 5000L)
        ZeekrMediaProtocol.command(service, ZeekrMediaProtocol.CORE, 1) { it.writeStringArray(arrayOf("mediacenter")) }
    }

    private fun retryInit(generation: Int) {
        if (initRetry != null) return
        if (initAttempts >= 3) { note("media init retries exhausted"); startDirect(); return }
        val retry = Runnable {
            initRetry = null
            if (valid(generation)) core?.let(::initMedia)
        }
        initRetry = retry
        worker.postDelayed(retry, 2000L)
    }

    private fun cancelInitWait() {
        initRetry?.let { worker.removeCallbacks(it) }
        initDeadline?.let { worker.removeCallbacks(it) }
        initRetry = null
        initDeadline = null
    }

    private fun valid(generation: Int) = !closed && epoch == generation

    private fun register(generation: Int) = safely("register") {
        if (registered) return@safely
        val service = router ?: return@safely
        registered = true
        val owner = Any().also { registrationOwner = it }
        fun current() = valid(generation) && registrationOwner === owner
        var newToken: IBinder? = null
        var newMedia: IBinder? = null
        fun complete() {
            val receivedToken = newToken ?: return
            val receivedMedia = newMedia ?: return
            if (current()) {
                token = receivedToken
                media = receivedMedia
                ready()
            } else safely("discard stale registration") {
                ZeekrMediaProtocol.command(receivedMedia, ZeekrMediaProtocol.MEDIA, 5) { it.writeStrongBinder(receivedToken) }
            }
        }
        val music = createZeekrMusicClient(
            packageName = context.packageName,
            active = { current() },
            info = { playbackInfo },
            progress = { val current = snapshot; position(current.info, current.positionAt) },
            command = { command -> current() && control(generation, command) },
            focusLost = {
                worker.post {
                    if (current()) {
                        yielded = true
                        if (snapshot.info.playing) control(generation, CarPlayMediaButton.PAUSE)
                    }
                }
            },
        )
        val tokenReceiver = ZeekrCallbackBinder(ZeekrMediaProtocol.TOKEN_CALLBACK) { code, data, reply ->
            if (code != 1) return@ZeekrCallbackBinder false
            val received = data.readStrongBinder()
            worker.post {
                if (current()) note("registration token received present=${received != null}")
                newToken = received
                complete()
            }
            reply?.writeNoException()
            true
        }
        val registration = ZeekrCallbackBinder(ZeekrMediaProtocol.REGISTER_CALLBACK) { code, _, reply ->
            if (code !in 1..2) return@ZeekrCallbackBinder false
            reply?.writeNoException()
            reply?.writeStrongBinder(if (code == 1) tokenReceiver else music)
            true
        }
        // SDK uses the package plus Android user ID when running under a secondary user.
        val user = Process.myUid() / 100000
        val packageName = if (user == 0) context.packageName else "${context.packageName}:$user"
        val status = ZeekrMediaProtocol.route(service, "mediaCenterRegisterMusicNew", packageName, registration, routerDescriptor)
        if (status != 200) {
            registered = false
            registrationOwner = null
            note("registration rejected code=$status")
            return@safely
        }
        val extension = createZeekrSelectionEndpoint { source, display ->
            current() && source == ZeekrMediaProtocol.SOURCE && display == 0 && control(generation, CarPlayMediaButton.PLAY)
        }
        safely("extension registration") {
            val extensionStatus = ZeekrMediaProtocol.route(service, "mediaCenterRegisterExtMediaClient", packageName, extension, routerDescriptor)
            note("extension registration code=$extensionStatus")
        }
        val mediaReceiver = ZeekrCallbackBinder(ZeekrMediaProtocol.MEDIA_CALLBACK) { code, data, reply ->
            when (code) {
                2 -> {
                    val received = data.readStrongBinder()
                    worker.post {
                        if (current()) note("media Binder received present=${received != null}")
                        newMedia = received
                        complete()
                    }
                    reply?.writeNoException()
                }
                1 -> { reply?.writeNoException(); reply?.writeStrongBinder(newToken) }
                else -> return@ZeekrCallbackBinder false
            }
            true
        }
        val mediaStatus = ZeekrMediaProtocol.route(service, "mediaCenterUpdateMediaContentNew", "", mediaReceiver, routerDescriptor)
        note("registration accepted; media service code=$mediaStatus; awaiting token")
    }

    private fun ready() = safely("publish source") {
        if (token == null || media == null) return@safely
        mediaCommand(8) { it.writeIntArray(intArrayOf(ZeekrMediaProtocol.SOURCE)) }
        note("source registered")
        // Hold the media-center focus from the start like the working bridge, then keep it on a
        // steady tick: the CarPlay now-playing "playing" flag is unreliable, so progressTick alone
        // (which only runs while playing) never asserted focus and the car reclaimed the source.
        yielded = false
        claimSource()
        worker.removeCallbacks(focusTick)
        worker.postDelayed(focusTick, 1000L)
        publish()
    }

    private fun control(generation: Int, command: Int): Boolean {
        if (!valid(generation) || !controlsEnabled) return false
        val owner = registrationOwner
        val sent = send(command)
        if (sent && command == CarPlayMediaButton.PLAY) worker.post {
            if (valid(generation) && registrationOwner === owner) safely("select source") { yielded = false; claimSource() }
        }
        note("control=$command forwarded=$sent")
        return sent
    }

    /** requestPlay(6) assigns the media focus; updateCurrentSourceType(9) then declares us (needs focus). */
    private fun claimSource() {
        mediaCommand(6)
        mediaCommand(9) { it.writeInt(ZeekrMediaProtocol.SOURCE) }
    }

    /** Query who the media center currently has selected: self / other / empty. */
    private fun queryFocusState(): String = runCatching {
        val service = media ?: return "unknown"
        val identity = token ?: return "unknown"
        val selected = ZeekrMediaProtocol.call(service, ZeekrMediaProtocol.MEDIA, 44,
            { it.writeStrongBinder(identity) }) { it.readString() }
        when {
            selected.isNullOrEmpty() -> "empty"
            selected == context.packageName || selected.startsWith("${context.packageName}:") -> "self"
            else -> "other"
        }
    }.getOrDefault("unknown")

    /**
     * Hold the media focus like the working media bridge: re-acquire on every PLAYING tick instead of
     * only on the rising edge. The CarPlay media stream flaps play/pause, and the car's own source UI
     * can steal selection, so a single requestPlay does not stick (focus=empty). We re-claim whenever
     * we are not the selected source, unless the car explicitly revoked focus (yielded via focusLost).
     */
    private fun ensureFocusHeld() {
        if (yielded) return
        val state = queryFocusState()
        if (state != "self" && state != "unknown") runCatching { claimSource() }
        if (state != lastFocusState) { lastFocusState = state; note("source focus=$state") }
    }

    private fun queuePublish() {
        if (publishQueued.compareAndSet(false, true)) worker.post {
            publishQueued.set(false)
            if (!closed) safely("update") { publish() }
        }
    }

    private fun publish() {
        if (closed || token == null || media == null) return
        val next = snapshot
        if (previous?.info?.playing == false && next.info.playing) yielded = false
        if (autoSelect && !yielded && next.info.playing && previous?.info?.playing != true) claimSource()
        if (next.artwork !== cover) {
            cover = next.artwork
            var pendingFile: File? = null
            coverUri = runCatching { next.artwork?.let { bitmap ->
                folder.mkdirs()
                val file = File(folder, "${UUID.randomUUID()}.jpg").also { pendingFile = it }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.zeekr-artwork", file)
                covers.addLast(uri)
                coverFiles.addLast(file)
                // Allow an in-flight reader of the previous image, but bound retained files/grants.
                while (covers.size > 2) {
                    context.revokeUriPermission(covers.removeFirst(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    coverFiles.removeFirst().delete()
                }
                uri
            } }.onFailure {
                pendingFile?.delete()
                note("artwork unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()
        }
        publication = Publication(next.info, coverUri, next.positionAt)
        val old = previous
        if (old == null || old.info.copy(elapsedMillis = null) != next.info.copy(elapsedMillis = null) || old.artwork !== next.artwork) {
            mediaCommand(7) { it.writeStrongBinder(playbackInfo) }
        }
        if (old == null || old.info.elapsedMillis != next.info.elapsedMillis) {
            mediaCommand(11) { it.writeLong(position(next.info, next.positionAt)) }
        }
        previous = next
        worker.removeCallbacks(progressTick)
        if (next.info.playing) worker.postDelayed(progressTick, 1000L)
    }

    private fun position(info: CarPlayNowPlaying, anchor: Long): Long {
        val elapsed = info.elapsedMillis ?: return 0L
        val value = (elapsed + if (info.playing) (SystemClock.elapsedRealtime() - anchor).coerceAtLeast(0L) else 0L).coerceAtLeast(0L)
        return info.durationMillis?.takeIf { it > 0L }?.let { value.coerceAtMost(it) } ?: value
    }

    private fun mediaCommand(code: Int, write: (Parcel) -> Unit = {}) {
        val service = media ?: return
        val identity = token ?: return
        val accepted = ZeekrMediaProtocol.call(service, ZeekrMediaProtocol.MEDIA, code, {
            it.writeStrongBinder(identity); write(it)
        }) {
            when (code) {
                5, 6, 7 -> it.readInt() != 0
                8, 9, 11 -> true // These OEM methods return void, with no int in the reply.
                else -> error("Unknown media reply contract $code")
            }
        }
        check(accepted) { "Media command $code rejected" }
    }

    private val playbackInfo = createZeekrPlaybackInfo(
        packageName = context.packageName,
        snapshot = {
            val current = publication
            if (closed) CarPlayNowPlaying() else current.info.copy(elapsedMillis = position(current.info, current.positionAt))
        },
        artwork = {
            if (closed) null else publication.artwork?.also { uri ->
                val caller = Binder.getCallingUid()
                val identity = Binder.clearCallingIdentity()
                try {
                    context.packageManager.getPackagesForUid(caller).orEmpty().forEach {
                        context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } finally { Binder.restoreCallingIdentity(identity) }
            }
        },
        launch = { display ->
            val intent = Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= 26 && display >= 0) launchDisplayId = display
            }
            PendingIntent.getActivity(context, display, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        },
        available = { !closed },
    )

    private fun safely(operation: String, action: () -> Unit) {
        try { action() } catch (error: Exception) { note("$operation failed: ${error.javaClass.simpleName}") }
    }

    companion object {
        val DIRECT_COMPONENT = ComponentName("com.zeekr.mediacenter", "ecarx.xsf.mediacenter.ZeekrMediaCenterService")
        val COMPONENT = ComponentName("com.zeekr.coreservice", "com.zeekr.coreservice.CoreService")
        private val thread by lazy { HandlerThread("diplay-zeekr-media").apply { start() } }
        private fun note(message: String) {
            Log.i("DiPlay-ZeekrMedia", message)
            WheelKeyLog.note("Zeekr media: $message")
        }
    }
}

class ZeekrArtworkProvider : FileProvider()
