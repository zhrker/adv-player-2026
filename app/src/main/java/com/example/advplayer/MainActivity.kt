package com.example.advplayer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.Executors

data class ShareConfig(
    val name: String,
    val host: String,
    val share: String,
    val domain: String,
    val user: String,
    val pass: String
)

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
)

class MainActivity : AppCompatActivity() {

    private lateinit var sharePanel: View
    private lateinit var browserPanel: View
    private lateinit var playerPanel: View
    private lateinit var btnAddShare: Button
    private lateinit var shareListView: ListView
    private lateinit var btnBackShares: Button
    private lateinit var btnUp: Button
    private lateinit var breadcrumbText: TextView
    private lateinit var sortSpinner: Spinner
    private lateinit var fileListView: ListView
    private lateinit var browserStatus: TextView
    private lateinit var btnBackBrowser: Button
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var playerStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var btnStop: Button

    private lateinit var prefs: android.content.SharedPreferences
    private val executor = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())

    private var shares = mutableListOf<ShareConfig>()
    private var shareAdapter: ArrayAdapter<String>? = null
    private var currentShare: ShareConfig? = null
    private var currentPath = ""
    private var fileItems = mutableListOf<FileItem>()
    private var fileAdapter: FileAdapter? = null
    private var sortMode = 0

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var seeking = false

    private val videoExtensions = listOf(
        ".rmvb", ".rm", ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
        ".ts", ".m2ts", ".mpg", ".mpeg", ".m4v", ".webm", ".3gp"
    )
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharePanel = findViewById(R.id.sharePanel)
        browserPanel = findViewById(R.id.browserPanel)
        playerPanel = findViewById(R.id.playerPanel)
        btnAddShare = findViewById(R.id.btnAddShare)
        shareListView = findViewById(R.id.shareListView)
        btnBackShares = findViewById(R.id.btnBackShares)
        btnUp = findViewById(R.id.btnUp)
        breadcrumbText = findViewById(R.id.breadcrumbText)
        sortSpinner = findViewById(R.id.sortSpinner)
        fileListView = findViewById(R.id.fileListView)
        browserStatus = findViewById(R.id.browserStatus)
        btnBackBrowser = findViewById(R.id.btnBackBrowser)
        videoLayout = findViewById(R.id.videoLayout)
        playerStatus = findViewById(R.id.playerStatus)
        seekBar = findViewById(R.id.seekBar)
        timeText = findViewById(R.id.timeText)
        btnStop = findViewById(R.id.btnStop)

        initSecurePrefs()
        initPlayer()
        startProgressLoop()

        val sortOptions = arrayOf("按名称", "按日期", "按大小")
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortMode = position
                sortAndRefresh()
            }
        }

        btnAddShare.setOnClickListener { showAddShareDialog() }
        btnBackShares.setOnClickListener { showShares() }
        btnUp.setOnClickListener {
            currentShare?.let { s ->
                val idx = currentPath.lastIndexOf('/')
                val parent = if (idx < 0) "" else currentPath.substring(0, idx)
                browseShare(s, parent)
            }
        }
        btnBackBrowser.setOnClickListener { showBrowser() }
        btnStop.setOnClickListener { stopPlayback() }

        shareListView.setOnItemClickListener { _, _, position, _ ->
            if (position in shares.indices) browseShare(shares[position], "")
        }

        fileListView.setOnItemClickListener { _, _, position, _ ->
            if (position in fileItems.indices) {
                val item = fileItems[position]
                if (item.isDir) {
                    currentShare?.let { browseShare(it, item.path) }
                } else {
                    playFile(item)
                }
            }
        }

        loadShares()
        showShares()
    }

    private fun initSecurePrefs() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            this,
            "smb_player_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun loadShares() {
        try {
            val raw = prefs.getString("shares", "[]") ?: "[]"
            val arr = JSONArray(raw)
            shares.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                shares.add(
                    ShareConfig(
                        name = o.optString("name"),
                        host = o.optString("host"),
                        share = o.optString("share"),
                        domain = o.optString("domain"),
                        user = o.optString("user"),
                        pass = o.optString("pass")
                    )
                )
            }
        } catch (e: Exception) {
            shares.clear()
        }
        refreshShareList()
    }

    private fun saveShares() {
        val arr = JSONArray()
        shares.forEach { s ->
            val o = JSONObject()
            o.put("name", s.name)
            o.put("host", s.host)
            o.put("share", s.share)
            o.put("domain", s.domain)
            o.put("user", s.user)
            o.put("pass", s.pass)
            arr.put(o)
        }
        prefs.edit().putString("shares", arr.toString()).apply()
    }

    private fun refreshShareList() {
        val names = shares.map { it.name }
        shareAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        shareListView.adapter = shareAdapter
    }

    private fun showAddShareDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_add_share, null)
        val name = v.findViewById<EditText>(R.id.editName)
        val host = v.findViewById<EditText>(R.id.editHost)
        val share = v.findViewById<EditText>(R.id.editShare)
        val domain = v.findViewById<EditText>(R.id.editDomain)
        val user = v.findViewById<EditText>(R.id.editUser)
        val pass = v.findViewById<EditText>(R.id.editPass)

        AlertDialog.Builder(this)
            .setTitle("添加共享目录")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val cfg = ShareConfig(
                    name = name.text.toString().trim().ifEmpty { "未命名共享" },
                    host = host.text.toString().trim(),
                    share = share.text.toString().trim(),
                    domain = domain.text.toString().trim(),
                    user = user.text.toString().trim(),
                    pass = pass.text.toString()
                )
                if (cfg.host.isEmpty() || cfg.share.isEmpty()) {
                    Toast.makeText(this, "主机和共享名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                shares.add(cfg)
                saveShares()
                refreshShareList()
                Toast.makeText(this, "已保存：${cfg.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showShares() {
        stopPlaybackQuiet()
        sharePanel.visibility = View.VISIBLE
        browserPanel.visibility = View.GONE
        playerPanel.visibility = View.GONE
    }

    private fun showBrowser() {
        sharePanel.visibility = View.GONE
        browserPanel.visibility = View.VISIBLE
        playerPanel.visibility = View.GONE
    }

    private fun showPlayer() {
        sharePanel.visibility = View.GONE
        browserPanel.visibility = View.GONE
        playerPanel.visibility = View.VISIBLE
    }

    private fun browseShare(cfg: ShareConfig, path: String) {
        currentShare = cfg
        currentPath = path
        showBrowser()
        breadcrumbText.text = if (path.isEmpty()) {
            "${cfg.name} \\"
        } else {
            "${cfg.name} \\ ${path.replace('/', '\\')}"
        }
        browserStatus.text = "正在读取目录..."
        fileItems.clear()
        fileAdapter = FileAdapter(fileItems)
        fileListView.adapter = fileAdapter

        executor.execute {
            val result = mutableListOf<FileItem>()
            var error: String? = null
            try {
                val client = SMBClient()
                client.connect(cfg.host).use { connection ->
                    val auth = if (cfg.user.isEmpty()) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(cfg.user, cfg.pass.toCharArray(), cfg.domain)
                    }
                    val session = connection.authenticate(auth)
                    val disk = session.connectShare(cfg.share) as DiskShare
                    disk.use {
                        it.list(path, "*").forEach { f ->
                            val n = f.fileName
                            if (n == "." || n == "..") return@forEach
                            val isDir = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                            val full = if (path.isEmpty()) n else "$path/$n"
                            result.add(FileItem(n, full, isDir, f.endOfFile, f.lastWriteTime.toEpochMillis()))
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "未知错误"
            }

            runOnUiThread {
                if (error != null) {
                    browserStatus.text = "读取失败：$error"
                } else {
                    fileItems.clear()
                    fileItems.addAll(result)
                    sortAndRefresh()
                    browserStatus.text = "共 ${fileItems.size} 项"
                }
            }
        }
    }

    private fun sortAndRefresh() {
        val sorted = when (sortMode) {
            1 -> fileItems.sortedByDescending { it.modified }
            2 -> fileItems.sortedByDescending { it.size }
            else -> fileItems.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        fileItems.clear()
        fileItems.addAll(sorted)
        val dirs = fileItems.filter { it.isDir }
        val files = fileItems.filter { !it.isDir }
        fileItems.clear()
        fileItems.addAll(dirs)
        fileItems.addAll(files)
        fileAdapter?.notifyDataSetChanged()
    }

    private fun playFile(item: FileItem) {
        val cfg = currentShare ?: return
        showPlayer()
        val uri = buildSmbUri(cfg, item.path)
        if (uri == null) {
            playerStatus.text = "地址构造失败"
            return
        }
        val p = player
        if (p == null) {
            playerStatus.text = "播放器未就绪"
            return
        }
        try {
            p.stop()
            val media = Media(libVLC, uri).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=1000")
            }
            p.media = media
            media.release()
            p.play()
            playerStatus.text = "正在连接：${item.name}"
        } catch (e: Exception) {
            playerStatus.text = "播放失败：${e.message}"
        }
    }

    private fun buildSmbUri(cfg: ShareConfig, path: String): Uri? {
        return try {
            val userPart = if (cfg.user.isEmpty()) {
                ""
            } else {
                val u = URLEncoder.encode(cfg.user, "UTF-8")
                val p = URLEncoder.encode(cfg.pass, "UTF-8")
                "$u:$p@"
            }
            val host = cfg.host.trim()
            val shareName = cfg.share.trim('/')
            val cleanPath = path.trim('/')
            val tail = if (cleanPath.isEmpty()) "" else "/$cleanPath"
            Uri.parse("smb://$userPart$host/$shareName$tail")
        } catch (e: Exception) {
            null
        }
    }

    private fun stopPlayback() {
        stopPlaybackQuiet()
        playerStatus.text = "已停止"
        seekBar.progress = 0
        timeText.text = "00:00 / 00:00"
    }

    private fun stopPlaybackQuiet() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun initPlayer() {
        try {
            val options = arrayListOf(
                "--no-video-title-show",
                "--network-caching=1000",
                "--file-caching=1000",
                "--avcodec-hw=any"
            )
            libVLC = LibVLC(this, options)
            player = MediaPlayer(libVLC).apply {
                attachViews(videoLayout, null, false, false)
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Playing -> runOnUiThread { playerStatus.text = "正在播放" }
                        MediaPlayer.Event.Buffering -> runOnUiThread {
                            playerStatus.text = "缓冲中 ${(event.buffering * 100).toInt()}%"
                        }
                        MediaPlayer.Event.EndReached -> runOnUiThread { playerStatus.text = "播放结束" }
                        MediaPlayer.Event.EncounteredError -> runOnUiThread {
                            playerStatus.text = "播放失败：账号密码或格式问题"
                        }
                    }
                }
            }
            playerStatus.text = "就绪"
        } catch (e: Exception) {
            playerStatus.text = "播放器初始化失败：${e.message}"
        }
    }

    private fun safeLength(): Long = try { player?.length ?: 0L } catch (e: Exception) { 0L }
    private fun safeTime(): Long = try { player?.time ?: 0L } catch (e: Exception) { 0L }

    private fun startProgressLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val len = safeLength()
                    if (!seeking && len > 0 && playerPanel.visibility == View.VISIBLE) {
                        val time = safeTime()
                        seekBar.max = len.toInt()
                        seekBar.progress = time.toInt()
                        timeText.text = "${formatTime(time)} / ${formatTime(len)}"
                    }
                } catch (e: Exception) {
                    // ignore
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDate(ms: Long): String {
        if (ms <= 0) return ""
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
        } catch (e: Exception) {
            ""
        }
    }

    private inner class FileAdapter(val items: MutableList<FileItem>) :
        ArrayAdapter<FileItem>(this@MainActivity, R.layout.file_row, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.file_row, parent, false)
            val icon = view.findViewById<ImageView>(R.id.fileIcon)
            val name = view.findViewById<TextView>(R.id.fileName)
            val meta = view.findViewById<TextView>(R.id.fileMeta)
            val item = items[position]

            name.text = item.name
            meta.text = if (item.isDir) {
                "文件夹 · ${formatDate(item.modified)}"
            } else {
                "${formatSize(item.size)} · ${formatDate(item.modified)}"
            }

            val lower = item.name.lowercase(Locale.getDefault())
            when {
                item.isDir -> icon.setImageResource(android.R.drawable.ic_menu_manage)
                imageExtensions.any { lower.endsWith(it) } -> {
                    icon.setImageResource(android.R.drawable.ic_menu_gallery)
                    loadImageThumb(item.path, icon)
                }
                videoExtensions.any { lower.endsWith(it) } ->
                    icon.setImageResource(android.R.drawable.ic_media_play)
                else -> icon.setImageResource(android.R.drawable.ic_menu_agenda)
            }
            return view
        }
    }

    private fun loadImageThumb(path: String, icon: ImageView) {
        val cfg = currentShare ?: return
        executor.execute {
            var bmp: Bitmap? = null
            try {
                val client = SMBClient()
                client.connect(cfg.host).use { connection ->
                    val auth = if (cfg.user.isEmpty()) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(cfg.user, cfg.pass.toCharArray(), cfg.domain)
                    }
                    val session = connection.authenticate(auth)
                    val disk = session.connectShare(cfg.share) as DiskShare
                    disk.use {
                        it.openFile(
                            path,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        ).use { f: File ->
                            val buf = ByteArray(300000)
                            val n = f.read(buf, 0L, 0, buf.size)
                            if (n > 0) bmp = BitmapFactory.decodeByteArray(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            runOnUiThread {
                if (bmp != null) icon.setImageBitmap(bmp)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            player?.stop()
            player?.detachViews()
            player?.release()
        } catch (e: Exception) {
            // ignore
        }
        player = null
        try {
            libVLC?.release()
        } catch (e: Exception) {
            // ignore
        }
        libVLC = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
