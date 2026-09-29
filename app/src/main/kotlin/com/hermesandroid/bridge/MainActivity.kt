package com.hermesandroid.bridge


import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.overlay.HermesPopupOverlay
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.net.NetworkInterface
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_RECORD = 1001
    }

    private lateinit var tvA11yStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvRelayAddr: TextView
    private lateinit var tvAuthCode: TextView
    private lateinit var indicatorA11y: View
    private lateinit var indicatorServer: View
    private lateinit var indicatorRelay: View
    private lateinit var indicatorAuth: View
    private lateinit var switchAccessibility: Switch
    private lateinit var switchOverlay: Switch
    private lateinit var switchScreenRecord: Switch
    private lateinit var switchRevival: Switch
    private lateinit var tvPairingCode: TextView
    private lateinit var btnRegenerate: Button
    private lateinit var etServerUrl: EditText
    private lateinit var tvRelayStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvAddress: TextView
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvRelayAddr = findViewById(R.id.tvRelayAddr)
        tvAuthCode = findViewById(R.id.tvAuthCode)
        indicatorA11y = findViewById(R.id.indicatorA11y)
        indicatorServer = findViewById(R.id.indicatorServer)
        indicatorRelay = findViewById(R.id.indicatorRelay)
        indicatorAuth = findViewById(R.id.indicatorAuth)
        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchScreenRecord = findViewById(R.id.switchScreenRecord)
        switchRevival = findViewById(R.id.switchRevival)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        btnRegenerate = findViewById(R.id.btnRegenerate)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvRelayStatus = findViewById(R.id.tvRelayStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvAddress = findViewById(R.id.tvAddress)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        setupPairingCode()
        setupPermissions()
        setupRelayConnection()
        setupSettingsEntry()

        updateConnectionInfo()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updatePermissionSwitches()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_RECORD) {
            if (resultCode == RESULT_OK && data != null) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                } else {
                    ScreenRecorder.setProjectionPermission(resultCode, data)
                    Toast.makeText(this, "Screen recording permission granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
            }
            updatePermissionSwitches()
        }
    }

    private fun setupPairingCode() {
        tvPairingCode.text = PairingManager.getCode()

        btnRegenerate.setOnClickListener {
            PairingManager.regenerateCode()
            tvPairingCode.text = PairingManager.getCode()
            updateStatus()
            Toast.makeText(this, "New pairing code generated", Toast.LENGTH_SHORT).show()
        }

        tvPairingCode.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes pairing code", PairingManager.getCode()))
            Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPermissions() {
        switchAccessibility.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && BridgeAccessibilityService.instance == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    StatusOverlay.show(this)
                }
            } else {
                StatusOverlay.hide(this)
            }
        }

        switchScreenRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !ScreenRecorder.hasPermission()) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                    updatePermissionSwitches()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_RECORD)
                }
            }
        }

        // Opt-in for the Termux auto-revival watchdog (RUN_COMMAND).
        // Default OFF — silent script execution must be explicitly enabled.
        switchRevival.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("hermes_bridge_prefs", MODE_PRIVATE)
                .edit().putBoolean("termux_revival_enabled", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Termux auto-revival ON" else "Termux auto-revival OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

    }

    private fun updatePermissionSwitches() {
        switchAccessibility.setOnCheckedChangeListener(null)
        switchOverlay.setOnCheckedChangeListener(null)
        switchScreenRecord.setOnCheckedChangeListener(null)
        switchRevival.setOnCheckedChangeListener(null)

        switchAccessibility.isChecked = BridgeAccessibilityService.instance != null
        switchOverlay.isChecked = Settings.canDrawOverlays(this)
        switchScreenRecord.isChecked = ScreenRecorder.hasPermission()
        switchRevival.isChecked =
            getSharedPreferences("hermes_bridge_prefs", MODE_PRIVATE)
                .getBoolean("termux_revival_enabled", false)

        setupPermissions()
    }

    private fun setupRelayConnection() {
        // 默认使用本机 Termux Bridge。
        // 如果用户以前保存过服务器地址，则继续使用保存的地址。
        // RelayClient 统一提供默认服务器地址。
        val relayUrl = RelayClient.serverUrl ?: ""
        etServerUrl.setText(relayUrl)

        RelayClient.onStatusChanged = { connected, message ->
            tvRelayStatus.text = message
            tvRelayStatus.setTextColor(
                if (connected) 0xFF4CAF50.toInt() else 0xFF888888.toInt()
            )
            btnDisconnect.visibility = if (connected || RelayClient.isConnected) View.VISIBLE else View.GONE
            btnConnect.text = if (RelayClient.isConnected) "CONNECTED" else "CONNECT"
            btnConnect.background = getDrawable(
                if (RelayClient.isConnected) R.drawable.bg_input_dark else R.drawable.bg_button_orange
            )
            btnConnect.setTextColor(
                if (RelayClient.isConnected) 0xFF4CAF50.toInt() else 0xFF1A1A1A.toInt()
            )
            updateStatus()
        }

        btnConnect.setOnClickListener {
            if (RelayClient.isConnected) return@setOnClickListener
            val url = etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val code = PairingManager.getCode()

            // RelayClient.connect() 会保存 serverUrl。
            // pairing code 统一来自 PairingManager，不再维护第二份。
            RelayClient.connect(url, code)
        }

        btnDisconnect.setOnClickListener {
            RelayClient.disconnect()
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "CONNECT"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(0xFF1A1A1A.toInt())
            updateStatus()
        }

        updateRelayButton()

    }

    private fun updateRelayButton() {
        if (RelayClient.isConnected) {
            btnDisconnect.visibility = View.VISIBLE
            btnConnect.text = "CONNECTED"
            btnConnect.background = getDrawable(R.drawable.bg_input_dark)
            btnConnect.setTextColor(0xFF4CAF50.toInt())
            tvRelayStatus.text = "Connected to ${RelayClient.serverUrl}"
            tvRelayStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "CONNECT"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(0xFF1A1A1A.toInt())
        }
    }

    private fun updateConnectionInfo() {
        val ip = getLocalIpAddress()
        tvAddress.text = "http://$ip:8765 (USB/LAN)"
    }

    private fun updateStatus() {
        val serviceRunning = BridgeAccessibilityService.instance != null
        val relayConnected = RelayClient.isConnected

        tvA11yStatus.text = if (serviceRunning) "active" else "inactive"
        tvA11yStatus.setTextColor(if (serviceRunning) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
        indicatorA11y.setBackgroundResource(
            if (serviceRunning) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_grey
        )

        tvServerStatus.text = "8765"
        tvServerStatus.setTextColor(0xFF4CAF50.toInt())

        if (relayConnected) {
            tvRelayAddr.text = RelayClient.serverUrl
            tvRelayAddr.setTextColor(0xFF4CAF50.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_green)
        } else if (!RelayClient.serverUrl.isNullOrBlank()) {
            tvRelayAddr.text = "disconnected"
            tvRelayAddr.setTextColor(0xFF888888.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_red)
        } else {
            tvRelayAddr.text = "—"
            tvRelayAddr.setTextColor(0xFF555555.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_grey)
        }

        tvAuthCode.text = PairingManager.getCode()
        tvAuthCode.setTextColor(0xFF4CAF50.toInt())
    }

    private fun setupSettingsEntry() {
        findViewById<Button>(R.id.btnSettings)?.setOnClickListener { showHermesSettings() }
    }

    private fun showHermesSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 4)
        }

        var currentGroup = box

        fun addSetting(
            view: View,
            params: ViewGroup.LayoutParams? = null
        ) {
            if (params != null) {
                currentGroup.addView(view, params)
            } else {
                currentGroup.addView(view)
            }
        }

        val groupBodies = mutableListOf<LinearLayout>()
        val groupArrows = mutableListOf<TextView>()

        fun addGroup(title: String) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(4, 10, 4, 10)
                background =
                    getDrawable(
                        com.hermesandroid.bridge.R.drawable.bg_input_dark
                    )
            }

            val arrow = TextView(this).apply {
                text = "▶"
                textSize = 11f
                setTextColor(0xFFED7931.toInt())
                setPadding(4, 0, 10, 0)
            }

            val titleView = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
            }

            header.addView(
                arrow,
                LinearLayout.LayoutParams(28, 40)
            )

            header.addView(
                titleView,
                LinearLayout.LayoutParams(0, 40, 1f)
            )

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, 2, 0, 6)
            }

            // 关键：header 和 body 永远直接挂在最外层 box，
            // 不能挂到上一个 group 的 body 里面。
            box.addView(
                header,
                LinearLayout.LayoutParams(
                    -1,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 6
                }
            )

            box.addView(
                body,
                LinearLayout.LayoutParams(
                    -1,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            groupBodies.add(body)
            groupArrows.add(arrow)

            header.setOnClickListener {
                val opening = body.visibility != View.VISIBLE

                groupBodies.forEachIndexed { index, other ->
                    val open = opening && other === body
                    other.visibility =
                        if (open) View.VISIBLE else View.GONE
                    groupArrows[index].text =
                        if (open) "▼" else "▶"
                }
            }

            currentGroup = body
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFB8BBC5.toInt())
            setPadding(0, 14, 0, 4)
        }
        fun colorInput(value: Int): EditText = EditText(this).apply {
            setText(String.format("#%06X", 0xFFFFFF and value))
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(12, 6, 12, 6)
        }

        addGroup("连接与服务")
        addSetting(label("默认连接 IP / 地址"))
        val serverAddress = EditText(this).apply {
            setText(RelayClient.serverUrl ?: "127.0.0.1:8766")
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF777777.toInt())
            hint = "例如 192.168.1.100:8766"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(12, 6, 12, 6)
        }
        addSetting(serverAddress)

        addSetting(label("连接说明"))
        addSetting(TextView(this).apply {
            text = "保存后作为下次启动/自动重连的默认地址；不会自动断开当前连接。"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })

        addGroup("UI 外观")

        val opacity = SeekBar(this).apply {
            max = 100
            progress = (HermesPopupOverlay.overlayOpacity(this@MainActivity) * 100f).roundToInt()
        }
        val opacityText = TextView(this).apply {
            text = "${opacity.progress}%"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { opacityText.text = "$progress%" }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        addSetting(opacity); addSetting(opacityText)

        addSetting(label("文字颜色"))
        val textColor = colorInput(HermesPopupOverlay.textColor(this))
        addSetting(textColor)

        addSetting(label("背景颜色"))
        val backgroundColor = colorInput(HermesPopupOverlay.backgroundColor(this))
        addSetting(backgroundColor)

        addSetting(label("背景样式"))
        val backgroundStyle = Spinner(this)
        backgroundStyle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("圆角卡片", "纯色平面", "电路纹理"))
        backgroundStyle.setSelection(HermesPopupOverlay.backgroundStyle(this).coerceIn(0, 2))
        addSetting(backgroundStyle)

        addGroup("悬浮球")
        addSetting(label("悬浮球样式"))
        val ballStyle = Spinner(this)
        ballStyle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("圆形", "圆角方形", "方形"))
        ballStyle.setSelection(HermesPopupOverlay.ballShape(this).coerceIn(0, 2))
        addSetting(ballStyle)

        addSetting(label("悬浮球颜色"))
        val ballColor = colorInput(HermesPopupOverlay.ballColor(this))
        addSetting(ballColor)

        addSetting(label("悬浮球大小"))
        val ballSize = SeekBar(this).apply { max = 88; progress = HermesPopupOverlay.ballSize(this@MainActivity).coerceIn(32, 120) - 32 }
        val ballSizeText = TextView(this).apply { text = "${ballSize.progress + 32}dp"; setTextColor(0xFFED7931.toInt()); gravity = android.view.Gravity.END }
        ballSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { ballSizeText.text = "${progress + 40}dp" }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        addSetting(ballSize); addSetting(ballSizeText)

        addGroup("弹窗")
        addSetting(label("弹窗宽度"))
        /*
         * Popup width:
         * The setting value and the real WindowManager width use the same
         * 200..screenAvailableDp range.
         *
         * This avoids:
         *   slider says 600dp
         *   actual popup is silently clipped to ~380dp
         */
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val popupWidthMax = screenWidthDp
            .minus(16f)
            .toInt()
            .coerceAtLeast(200)
            .coerceAtMost(600)

        val popupWidth = SeekBar(this).apply {
            max = (popupWidthMax - 200).coerceAtLeast(0)

            val current = HermesPopupOverlay
                .popupWidth(this@MainActivity)
                .coerceIn(200, popupWidthMax)

            progress = current - 200
        }

        val popupWidthText = TextView(this).apply {
            text = "${popupWidth.progress + 200}dp"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }

        popupWidth.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(
                bar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                // IMPORTANT: +200, not +300.
                popupWidthText.text = "${progress + 200}dp"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        addSetting(popupWidth); addSetting(popupWidthText)

        addSetting(label("弹窗高度"))
        val popupHeight = SeekBar(this).apply {
            max = 45
            progress = HermesPopupOverlay.popupHeightPercent(this@MainActivity).coerceIn(45, 90) - 45
        }
        val popupHeightText = TextView(this).apply { text = "${popupHeight.progress + 45}% 屏幕高度"; setTextColor(0xFFED7931.toInt()); gravity = android.view.Gravity.END }
        popupHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { popupHeightText.text = "${progress + 45}% 屏幕高度" }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        addSetting(popupHeight); addSetting(popupHeightText)

        addGroup("显示与排版")

        val displayPrefs = getSharedPreferences(
            "hermes_overlay_prefs",
            MODE_PRIVATE
        )

        val textScale = SeekBar(this).apply {
            max = 60
            progress = (
                displayPrefs.getFloat(
                    "text_scale",
                    1.0f
                ) * 100f
            ).roundToInt().coerceIn(80, 140) - 80
        }

        val textScaleText = TextView(this).apply {
            text = "${textScale.progress + 80}%"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }

        addSetting(label("文字大小"))
        addSetting(textScale)
        addSetting(textScaleText)

        textScale.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    textScaleText.text = "${progress + 80}%"
                }

                override fun onStartTrackingTouch(
                    bar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    bar: SeekBar?
                ) {}
            }
        )

        val lineSpacing = SeekBar(this).apply {
            max = 80
            progress = (
                displayPrefs.getFloat(
                    "line_spacing",
                    0.20f
                ) * 100f
            ).roundToInt().coerceIn(0, 80)
        }

        val lineSpacingText = TextView(this).apply {
            text = "${lineSpacing.progress}%"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }

        addSetting(label("行距"))
        addSetting(lineSpacing)
        addSetting(lineSpacingText)

        lineSpacing.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    lineSpacingText.text = "${progress}%"
                }

                override fun onStartTrackingTouch(
                    bar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    bar: SeekBar?
                ) {}
            }
        )

        val letterSpacing = SeekBar(this).apply {
            max = 11
            progress = (
                (
                    displayPrefs.getFloat(
                        "letter_spacing",
                        0.0f
                    ) * 100f
                ).roundToInt() + 3
            ).coerceIn(0, 11)
        }

        val letterSpacingText = TextView(this).apply {
            text = "${letterSpacing.progress - 3}%"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }

        addSetting(label("字间距"))
        addSetting(letterSpacing)
        addSetting(letterSpacingText)

        letterSpacing.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    letterSpacingText.text = "${progress - 3}%"
                }

                override fun onStartTrackingTouch(
                    bar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    bar: SeekBar?
                ) {}
            }
        )

        val elementSpacing = SeekBar(this).apply {
            max = 12
            progress = displayPrefs.getInt(
                "element_spacing",
                2
            ).coerceIn(0, 12)
        }

        val elementSpacingText = TextView(this).apply {
            text = "${elementSpacing.progress}dp"
            setTextColor(0xFFED7931.toInt())
            gravity = android.view.Gravity.END
        }

        addSetting(label("元素间距"))
        addSetting(elementSpacing)
        addSetting(elementSpacingText)

        elementSpacing.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    elementSpacingText.text = "${progress}dp"
                }

                override fun onStartTrackingTouch(
                    bar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    bar: SeekBar?
                ) {}
            }
        )

        val resetDisplay = Button(this).apply {
            text = "显示设置恢复默认"

            setOnClickListener {
                textScale.progress = 100 - 80
                lineSpacing.progress = 20
                letterSpacing.progress = 0 + 3
                elementSpacing.progress = 2

                textScaleText.text = "100%"
                lineSpacingText.text = "20%"
                letterSpacingText.text = "0%"
                elementSpacingText.text = "2dp"
            }
        }

        addSetting(resetDisplay)

        fun saveUiSettings(makeDefault: Boolean = false) {
            val parse = { e: EditText, fallback: Int -> runCatching { android.graphics.Color.parseColor(e.text.toString().trim()) }.getOrDefault(fallback) }
            val address = serverAddress.text.toString().trim()
            if (address.isNotBlank()) RelayClient.serverUrl = address
            HermesPopupOverlay.saveUiStyle(
                this,
                opacity.progress / 100f,
                parse(textColor, HermesPopupOverlay.textColor(this)),
                parse(backgroundColor, HermesPopupOverlay.backgroundColor(this)),
                backgroundStyle.selectedItemPosition,
                parse(ballColor, HermesPopupOverlay.ballColor(this)),
                ballSize.progress + 32,
                ballStyle.selectedItemPosition,
                popupWidth.progress + 200,
                popupHeight.progress + 45
            )

            displayPrefs.edit()
                .putFloat(
                    "text_scale",
                    (textScale.progress + 80) / 100f
                )
                .putFloat(
                    "line_spacing",
                    lineSpacing.progress / 100f
                )
                .putFloat(
                    "letter_spacing",
                    (letterSpacing.progress - 3) / 100f
                )
                .putInt(
                    "element_spacing",
                    elementSpacing.progress
                )
                .apply()

            HermesPopupOverlay.refresh(this)

            if (makeDefault) HermesPopupOverlay.setCurrentUiAsDefault(this)
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode =
                View.OVER_SCROLL_IF_CONTENT_SCROLLS

            addView(
                box,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hermes UI 设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                saveUiSettings(false)
                Toast.makeText(this, "Hermes 设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("设为默认") { _, _ ->
                saveUiSettings(true)
                Toast.makeText(this, "当前连接地址和 UI 样式已设为默认", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            val resetButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            // The actual reset action lives in the dialog body so that all three
            // standard dialog buttons remain available.
            val reset = Button(this).apply {
                text = "重置环境"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("重置 Hermes 环境？")
                        .setMessage("将恢复到已保存的默认 UI 样式、悬浮球位置，并关闭当前 Hermes 页面。不会关闭系统权限。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重置") { _, _ ->
                            HermesPopupOverlay.resetEnvironment(this@MainActivity)
                            Toast.makeText(this@MainActivity, "Hermes 环境已重置", Toast.LENGTH_SHORT).show()
                        }.show()
                }
            }
            (box as ViewGroup).addView(reset, ViewGroup.LayoutParams(-1, dp(48)))
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun getLocalIpAddress(): String {
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "localhost"
    }
}
