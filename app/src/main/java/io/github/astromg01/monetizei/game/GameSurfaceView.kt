package io.github.astromg01.monetizei.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.astromg01.monetizei.data.RewardRepository
import io.github.astromg01.monetizei.domain.RewardRules
import kotlin.math.hypot
import kotlin.random.Random

class GameSurfaceView(
    context: Context,
    private val rewardRepository: RewardRepository
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private val backgroundPaint = Paint().apply { color = 0xFF111318.toInt() }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7CF3C6.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F7FA.toInt()
        textSize = 48f
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB5BEC8.toInt()
        textSize = 30f
    }

    @Volatile
    private var running = false
    private var renderThread: Thread? = null

    private var startedAt = 0L
    private var finishedAt = 0L
    private var score = 0
    private var targetX = 0f
    private var targetY = 0f
    private var targetRadius = 72f
    private var sessionFinished = false
    private var resultCommitted = false

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resetSession()
        running = true
        renderThread = Thread(this, "monetizei-game-loop").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        renderThread?.join(500)
        renderThread = null
    }

    override fun run() {
        while (running) {
            val frameStart = SystemClock.elapsedRealtime()
            update(frameStart)
            drawFrame()

            val elapsed = SystemClock.elapsedRealtime() - frameStart
            val sleepMs = (16L - elapsed).coerceAtLeast(2L)
            SystemClock.sleep(sleepMs)
        }
    }

    private fun update(now: Long) {
        if (!sessionFinished && now - startedAt >= RewardRules.SESSION_DURATION_MS) {
            sessionFinished = true
            finishedAt = now
        }

        if (sessionFinished && !resultCommitted) {
            val result = RewardRules.evaluate(score, finishedAt - startedAt)
            rewardRepository.creditSession(result)
            resultCommitted = true
        }
    }

    private fun drawFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

            val wallet = rewardRepository.getWallet()
            canvas.drawText("Monetizei", 48f, 90f, textPaint)
            canvas.drawText("Score: $score", 48f, 145f, secondaryTextPaint)
            canvas.drawText("Coins: ${wallet.softCoins}   XP: ${wallet.xp}", 48f, 190f, secondaryTextPaint)

            if (!sessionFinished) {
                canvas.drawCircle(targetX, targetY, targetRadius, targetPaint)
                val left = ((RewardRules.SESSION_DURATION_MS - (SystemClock.elapsedRealtime() - startedAt)) / 1000L)
                    .coerceAtLeast(0L)
                canvas.drawText("${left}s", 48f, height - 70f, textPaint)
            } else {
                drawFinished(canvas)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawFinished(canvas: Canvas) {
        val wallet = rewardRepository.getWallet()
        canvas.drawText("Sessão concluída", 48f, height / 2f - 50f, textPaint)
        canvas.drawText("Toque para jogar novamente", 48f, height / 2f + 10f, secondaryTextPaint)
        canvas.drawText("Saldo real: somente servidor", 48f, height / 2f + 65f, secondaryTextPaint)
        canvas.drawText("Coins: ${wallet.softCoins} | XP: ${wallet.xp}", 48f, height / 2f + 120f, secondaryTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true

        if (sessionFinished) {
            resetSession()
            return true
        }

        val distance = hypot(event.x - targetX, event.y - targetY)
        if (distance <= targetRadius) {
            score += 1
            targetRadius = (targetRadius - 0.8f).coerceAtLeast(44f)
            moveTarget()
        }
        return true
    }

    private fun resetSession() {
        score = 0
        targetRadius = 72f
        sessionFinished = false
        resultCommitted = false
        startedAt = SystemClock.elapsedRealtime()
        finishedAt = 0L
        moveTarget()
    }

    private fun moveTarget() {
        val safeWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val safeHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val margin = 120f
        targetX = Random.nextFloat() * (safeWidth - margin * 2f).coerceAtLeast(1f) + margin
        targetY = Random.nextFloat() * (safeHeight - margin * 4f).coerceAtLeast(1f) + margin * 2f
    }
}
