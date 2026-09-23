package app.fjj.stun.ui.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import app.fjj.stun.R
import app.fjj.stun.qr.AnimatedQrAssembler
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.ui.BaseActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 接收端：**连续扫码**页。一个 Activity 同时承担两件事 ——
 *
 * 1. 普通单张二维码的扫描（替代原先直接调 zxing 的 `CaptureActivity`，行为保持一致，
 *    扫到就把原文回传给调用方，后续 PIN 解密/导入逻辑一行没改）；
 * 2. 动画二维码的**边扫边拼**：把 [AnimatedQrAssembler] 喂满，收齐后回传完整载荷。
 *
 * ## 为什么不用库自带的 `CaptureManager`
 *
 * `CaptureManager` 的 `returnResult()` 默认行为是 `setResult` + `finish()` —— 每识别到一张码就退出页面，
 * 这正是单张扫描要的、也正是连续扫描**不能要**的（动画流里第一帧就把页面关掉了）。
 * 想复用就得继承并覆盖它，还要连带接受它对状态栏文案、蜂鸣器、超时提示的一整套默认行为。
 * 这里改用最直接的组合：`DecoratedBarcodeView.decodeContinuous()` + 自己做相机权限申请，
 * 行为完全可读，少一层需要反查源码才能理解的隐式逻辑。
 *
 * ## 线程模型
 *
 * `barcodeResult()` 在**相机线程**上回调，每秒可能来十几次。[AnimatedQrAssembler] 因此统一用
 * [lock] 串行化 —— 它同时被主线程的"过期清理"tick 碰，不加锁就属于两个线程并发改同一份可变状态。
 * 交给 UI 线程的只有**不可变的** `State` 快照 + 结果对象，不把可变对象递出去。
 */
class AnimatedQrScanActivity : BaseActivity(), BarcodeCallback {

    companion object {
        /**
         * 扫码结果：普通二维码的原文，或动画帧拼回来的完整载荷。
         * 两者格式一致（都是"密文串 / stun:// URI / JSON / Base64"之一），调用方走同一个导入分派。
         */
        const val EXTRA_QR_TEXT = "app.fjj.stun.extra.QR_TEXT"

        /** 多久没扫到任何帧就清掉进度。用户在挪手机、换码或已放弃时，不该把进度条停在一次作废的传输上。 */
        private const val STALE_TIMEOUT_MS = 8_000L

        private const val STALE_TICK_MS = 1_000L

        /** UI 刷新节流：连续解码每秒回调十几次，没必要每次都动控件。 */
        private const val UI_THROTTLE_MS = 120L

        private const val TAG = "AnimatedQrScanActivity"
    }

    private lateinit var preview: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator

    private val assembler = AnimatedQrAssembler()
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraGranted = false

    /** 主线程写、相机线程读（`barcodeResult` 的第一道闸）。加 volatile 让"已收工"立刻对相机线程可见。 */
    @Volatile
    private var finished = false
    private var lastUiAtMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPreview()
        } else {
            Toast.makeText(this, R.string.qr_stream_camera_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val staleTick = object : Runnable {
        override fun run() {
            if (finished) return
            val expired = synchronized(lock) {
                assembler.resetIfStale(System.currentTimeMillis(), STALE_TIMEOUT_MS)
            }
            if (expired) {
                val state = synchronized(lock) { assembler.snapshot() }
                renderState(state)
            }
            mainHandler.postDelayed(this, STALE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_stream_scan)

        preview = findViewById(R.id.qr_scan_preview)
        statusText = findViewById(R.id.tv_qr_scan_status)
        progress = findViewById(R.id.progress_qr_scan)
        findViewById<ImageButton>(R.id.btn_qr_scan_close).setOnClickListener { finish() }

        applyWindowInsets()
        renderState(null)

        preview.initializeFromIntent(intent)
        // 库自带的底部提示文案会跟我们自己的进度卡打架，留空由卡片承担
        preview.setStatusText("")
        // 只认 QR。默认解码器会把一维码 / DataMatrix / Aztec 全试一遍，而动画流每秒塞过来十几张图，
        // 把格式收窄到 QR 能明显压低单帧的检测耗时 —— 这直接决定接收端能跟上多少帧率。
        preview.barcodeView.decoderFactory = QrScanDecoderFactory.create()

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            startPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameraGranted) preview.resume()
        mainHandler.postDelayed(staleTick, STALE_TICK_MS)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(staleTick)
        if (cameraGranted) preview.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 相机线程回调。只做"喂帧 + 取快照"，把不可变数据丢给主线程。 */
    override fun barcodeResult(result: BarcodeResult) {
        if (finished) return
        val text = result.text ?: return
        // 成对取回：offer 与 snapshot 必须在同一个锁内，且顺序不能反（先喂帧再取状态）
        val (offer, state) = synchronized(lock) { assembler.offer(text) to assembler.snapshot() }
        // 节流放在投递之前：相机线程每秒回调十几次，没必要排十几个 Runnable 上去
        val force = offer !is AnimatedQrAssembler.Offer.Accepted
        val now = System.currentTimeMillis()
        if (!force && now - lastUiAtMs < UI_THROTTLE_MS) return
        lastUiAtMs = now
        mainHandler.post { applyOffer(text, offer, state) }
    }

    private fun applyOffer(
        scanned: String,
        offer: AnimatedQrAssembler.Offer,
        state: AnimatedQrAssembler.State?,
    ) {
        if (finished) return
        when (offer) {
            AnimatedQrAssembler.Offer.NotAnimatedFrame -> {
                // 不是动画帧 ⇒ 用户扫的是普通单张二维码，直接收工。
                // 但**只在还没开始拼接时**才允许退出：动画流里万一有帧被误读成普通码，
                // 中途退出会把已经攒了几十帧的进度全丢掉。没有进行中的会话时快照必为 null。
                if (state == null) finishWithText(scanned)
            }

            is AnimatedQrAssembler.Offer.Corrupt -> renderState(state)

            is AnimatedQrAssembler.Offer.Accepted -> renderState(state)

            // 靠修复帧（若干源分片的异或）补上了缺失的片。进度一样要刷 —— 到了"最后一段"，
            // 进展基本都是这么来的（原始片自己播过来太慢），所以必须上屏。
            is AnimatedQrAssembler.Offer.Repaired -> renderState(state)

            is AnimatedQrAssembler.Offer.Completed ->
                finishWithText(String(offer.payload, Charsets.UTF_8))

            is AnimatedQrAssembler.Offer.Rejected -> {
                // 状态已被重组器清空，UI 跟着回到"等待"
                renderState(null)
                Toast.makeText(this, rejectionMessage(offer.reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderState(state: AnimatedQrAssembler.State?) {
        if (state == null || !state.isActive) {
            statusText.setText(R.string.qr_stream_scan_waiting)
            progress.setProgressCompat(0, false)
            return
        }
        statusText.text = getString(
            R.string.qr_stream_scan_progress_format,
            state.received,
            state.totalFrames,
            state.percent,
        )
        progress.setProgressCompat(state.percent, true)
    }

    private fun rejectionMessage(reason: AnimatedQrAssembler.RejectReason): Int = when (reason) {
        AnimatedQrAssembler.RejectReason.CHECKSUM_MISMATCH -> R.string.qr_stream_scan_checksum_failed
        AnimatedQrAssembler.RejectReason.PAYLOAD_TOO_LARGE -> R.string.qr_stream_scan_too_large
    }

    private fun startPreview() {
        cameraGranted = true
        preview.decodeContinuous(this)
        preview.resume()
    }

    private fun finishWithText(text: String) {
        if (finished) return
        finished = true
        StunLogger.i(TAG, "Scan finished, payload length=${text.length}")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, text))
        finish()
    }

    /**
     * 相机预览是暗的（根布局纯黑 + centerCrop 黑边）。[BaseActivity] 默认的 auto 风格在**浅色主题**下
     * 会放行深色状态栏图标（`isAppearanceLightStatusBars = !isDark`），压在暗底上几乎看不见，
     * 这里强制 dark 风格 ⇒ 白色图标。scrim 传 [Color.TRANSPARENT] 是为了不叠
     * `enableEdgeToEdge()` 默认的那层导航栏遮罩（浅色模式下是 0xE6 的白，会在预览底部压出一条白杠）。
     */
    override fun statusBarStyle(): SystemBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)

    override fun navigationBarStyle(): SystemBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)

    /**
     * 让出状态栏 / 导航栏 / 刘海。边到边开启后内容会一直画到系统栏底下，不主动让位的话
     * 顶栏会钻进状态栏图标里、底部进度卡会被导航栏压住。
     *
     * 四个方向都算上是因为本页 `screenOrientation=fullSensor`：**横屏**时三键导航和刘海会跑到侧边，
     * 只处理上下会让关闭按钮和进度卡被侧边系统栏盖住。
     * 一律用物理 left/right 配物理 padding/margin —— insets 的 left/right 是物理值，
     * 拿 start/end 去加会在 RTL 下左右对错位。
     */
    private fun applyWindowInsets() {
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        val topBar = findViewById<View>(R.id.qr_scan_top_bar)
        val topBarBaseLeft = topBar.paddingLeft
        val topBarBaseTop = topBar.paddingTop
        val topBarBaseRight = topBar.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val b = insets.getInsets(bars)
            v.updatePadding(
                left = topBarBaseLeft + b.left,
                top = topBarBaseTop + b.top,
                right = topBarBaseRight + b.right,
            )
            insets
        }

        val card = findViewById<View>(R.id.qr_scan_status_card)
        val cardParams = card.layoutParams as FrameLayout.LayoutParams
        val cardBaseLeft = cardParams.leftMargin
        val cardBaseRight = cardParams.rightMargin
        val cardBaseBottom = cardParams.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(card) { v, insets ->
            val b = insets.getInsets(bars)
            (v.layoutParams as FrameLayout.LayoutParams).updateMargins(
                left = cardBaseLeft + b.left,
                right = cardBaseRight + b.right,
                bottom = cardBaseBottom + b.bottom,
            )
            v.requestLayout()
            insets
        }
    }
}
