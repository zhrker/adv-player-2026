package com.example.advplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var pathEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var playBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var videoLayout: VLCVideoLayout

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pathEdit = findViewById(R.id.pathEdit)
        userEdit = findViewById(R.id.userEdit)
        passEdit = findViewById(R.id.passEdit)
        playBtn = findViewById(R.id.playBtn)
        stopBtn = findViewById(R.id.stopBtn)
        seekBar = findViewById(R.id.seekBar)
        timeText = findViewById(R.id.timeText)
        statusText = findViewById(R.id.statusText)
        videoLayout = findViewById(R.id.videoLayout)

        playBtn.setOnClickListener { startPlayback() }
        stopBtn.setOnClickListener { stopPlayback() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val len = safeLength()
                    timeText.text = "${formatTime(progress.toLong())} / ${formatTime(len)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                val target = (seekBar?.progress ?: 0).toLong()
                try {
                    player?.time = target
                } catch (e: Exception) {
                    statusText.text = "跳转失败：${e.message}"
                }
            }
        })

        initPlayer()
        startProgressLoop()
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
                        MediaPlayer.Event.Playing -> runOnUiThread {
                            statusText.text = "正在播放"
                        }
                        MediaPlayer.Event.Buffering -> runOnUiThread {
                            statusText.text = "缓冲中 ${(event.buffering * 100).toInt()}%"
                        }
                        MediaPlayer.Event.EndReached -> runOnUiThread {
                            statusText.text = "播放结束"
                        }
                        MediaPlayer.Event.EncounteredError -> runOnUiThread {
                            statusText.text = "播放失败：请检查路径、账号密码和文件格式"
                        }
                    }
                }
            }
            statusText.text = "就绪，请输入 SMB 地址"
        } catch (e: Exception) {
            statusText.text = "播放器初始化失败：${e.message}"
        }
    }

    private fun buildSmbUri(input: String, user: String, pass: String): Uri? {
        var s = input.trim()
        if (s.isEmpty()) return null

        when {
            s.startsWith("\\\\", ignoreCase = false) -> {
                s = "smb:" + s.replace('\\', '/')
            }
            s.startsWith("smb://", ignoreCase = true) -> {
                // already correct
            }
            else -> {
                s = "smb://" + s.replace('\\', '/')
            }
        }

        val parsed = Uri.parse(s)
        val host = parsed.host ?: return null
        val path = parsed.path ?: ""
        val auth = if (user.isNotEmpty()) {
            val u = URLEncoder.encode(user, "UTF-8")
            val p = URLEncoder.encode(pass, "UTF-8")
            "$u:$p@"
        } else {
            ""
        }

        return try {
            Uri.parse("smb://$auth$host$path")
        } catch (e: Exception) {
            null
        }
    }

    private fun startPlayback() {
        val raw = pathEdit.text.toString()
        if (raw.isBlank()) {
            Toast.makeText(this, "请先填 SMB 路径", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = buildSmbUri(raw, userEdit.text.toString(), passEdit.text.toString())
        if (uri == null) {
            Toast.makeText(this, "SMB 地址格式不对", Toast.LENGTH_SHORT).show()
            return
        }

        val p = player
        if (p == null) {
            Toast.makeText(this, "播放器还没准备好", Toast.LENGTH_SHORT).show()
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
            statusText.text = "连接中..."
        } catch (e: Exception) {
            statusText.text = "启动播放失败：${e.message}"
        }
    }

    private fun stopPlayback() {
        try {
            player?.stop()
            statusText.text = "已停止"
            seekBar.progress = 0
            timeText.text = "00:00 / 00:00"
        } catch (e: Exception) {
            statusText.text = "停止失败：${e.message}"
        }
    }

    private fun safeLength(): Long = try {
        player?.length ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun safeTime(): Long = try {
        player?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun startProgressLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val len = safeLength()
                    if (!seeking && len > 0) {
                        val time = safeTime()
                        seekBar.max = len.toInt()
                        seekBar.progress = time.toInt()
                        timeText.text = "${formatTime(time)} / ${formatTime(len)}"
                    }
                } catch (e: Exception) {
                    // ignore UI refresh errors
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
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
        super.onDestroy()
    }
}
