package com.vrquest

import android.graphics.Color
import kotlin.math.*
import kotlin.random.Random

/** A 3D "world" game played with your real hands over the passthrough camera / environment. */
abstract class WorldGame {
    open val gloves = false
    open val gunHands = false
    @Volatile var shakeX = 0f
    @Volatile var shakeY = 0f
    open fun punching(slot: Int) = false
    open fun muzzle(slot: Int) = 0f
    abstract fun update(dt: Float, now: Long, hands: List<HandState>, scene: VrScene)
    abstract fun draw(g: VrRenderer.Gfx)
}

class Enemy(var yaw: Float, var r: Float) {
    var hp = 18f; var t = 0f; var state = 0; var flash = 0f; var side = 1; var speed = 1f
    var phase = Random.nextFloat() * 6f; var yaw0 = 0f; var scale = 1f; var hit = false
    fun pos() = V3(sin(yaw) * r, -0.7f * scale, -cos(yaw) * r)
}

class Particle(var p: V3, var v: V3, var life: Float, val col: Int)

class ArenaGame(val mode: Int, private val host: VrHost) : WorldGame() {
    companion object { const val BOX = 0; const val ZOMBIE = 1; const val GORILLA = 2 }
    override val gloves get() = mode != ZOMBIE
    override val gunHands get() = mode == ZOMBIE

    private var hp = 100f; private var score = 0; private var wave = 1; private var kills = 0
    private val enemies = ArrayList<Enemy>(); private val parts = ArrayList<Particle>()
    private val punchT = FloatArray(2); private val cool = FloatArray(2); private val muz = FloatArray(2)
    private val wasDown = BooleanArray(2); private val aimOn = BooleanArray(2); private val aim = arrayOf(V3(0f, 0f, -1f), V3(0f, 0f, -1f))
    private var flashRed = 0f; private var flashWhite = 0f; private var time = 0f; private var spawnT = 1f
    private var exitHold = 0f; private var over = 0f; private var padWas = false; private var started = false
    private var headYaw = 0f
    override fun punching(slot: Int) = punchT[slot.coerceIn(0, 1)] > 0f
    override fun muzzle(slot: Int) = muz[slot.coerceIn(0, 1)]

    private fun angDiff(a: Float, b: Float): Float { var d = a - b; while (d > PI) d -= (2 * PI).toFloat(); while (d < -PI) d += (2 * PI).toFloat(); return d }

    private fun reset() {
        hp = 100f; wave = 1; kills = 0; score = 0; enemies.clear(); parts.clear(); over = 0f; spawnT = 1.5f
        if (mode == BOX) spawnBoxer()
        val msg = when (mode) { BOX -> "Boxing: punch the opponent! Dodge by moving your head."; ZOMBIE -> "Make a finger-gun, flick your thumb to shoot!"; else -> "Gorilla Tag: slap the gorillas before they tag you!" }
        host.toast(msg + "  (Exit: open both palms)")
    }
    private fun spawnBoxer() { val e = Enemy(headYaw, 1.9f); e.hp = 60f + wave * 20f; e.t = 1f; enemies.add(e) }

    private fun localHit(e: Enemy, dir: V3): FloatArray? {
        val c = e.pos(); val cl = c.len(); val dd = dir.dot(c * (1f / cl)); if (dd < 0.05f) return null
        val hit = dir * (cl / dd); val rel = hit - c
        val hz = hypot(c.x, c.z); val sinT = -c.x / hz; val cosT = -c.z / hz
        val lx = (rel.x * cosT - rel.z * sinT) / e.scale; val ly = (rel.y + 0.0f) / e.scale
        return floatArrayOf(lx, ly, cl / dd)
    }

    private fun gloveXY(e: Enemy): FloatArray {
        val sw = sin(time * 2 + e.phase) * 0.05f
        return when (e.state) {
            1 -> floatArrayOf(e.side * 0.55f, 0.55f, 0.18f)
            2 -> floatArrayOf(0f, 0.25f, 0.42f)
            else -> floatArrayOf(e.side * 0.3f + sw, 0.35f, 0.16f)
        }
    }

    private fun burst(p: V3, col: Int, n: Int) { for (i in 0 until n) parts.add(Particle(p, V3(Random.nextFloat() - 0.5f, Random.nextFloat() * 0.8f, Random.nextFloat() - 0.5f) * 2.2f, 0.5f + Random.nextFloat() * 0.3f, col)) }

    private fun damage(e: Enemy, dmg: Float) {
        e.hp -= dmg; e.flash = 0.25f; flashWhite = 0.08f
        burst(e.pos() + V3(0f, 0.5f, 0f), if (mode == ZOMBIE) 0xFF7CFF4A.toInt() else 0xFFFFD34A.toInt(), 8)
        if (mode == BOX) { e.state = 3; e.t = 0.4f; e.r = min(2.4f, e.r + 0.12f) }
        if (e.hp <= 0f) {
            kills++; score += 10 * wave
            if (mode == BOX) { enemies.remove(e); wave++; host.toast("KO! Round $wave  Score $score"); Sfx.boom(); spawnBoxer() }
            else { enemies.remove(e); wave = 1 + kills / 8; host.toast("Score $score  Wave $wave") }
        }
    }

    private fun hurt(d: Float) {
        hp -= d; flashRed = 0.7f; shakeX = (Random.nextFloat() - 0.5f) * 0.12f; shakeY = (Random.nextFloat() - 0.5f) * 0.1f; Sfx.boom()
        if (hp <= 0f) { over = 3.5f; host.toast("GAME OVER  Score $score  (restarting...)") }
    }

    private fun doPunch(dir: V3) {
        var hitAny = false
        for (e in enemies.toList()) {
            val lh = localHit(e, dir) ?: continue
            if (mode == BOX && (e.state == 1 || e.state == 2)) {
                val gl = gloveXY(e)
                if (hypot(lh[0] - gl[0], lh[1] - gl[1]) < gl[2] + 0.25f) { e.state = 3; e.t = 0.6f; score += 2; Sfx.click(); burst(e.pos(), Color.WHITE, 6); hitAny = true; break }
            }
            if (abs(lh[0]) < 0.5f && lh[1] in -1.0f..1.0f) { damage(e, if (lh[1] > 0.45f) 16f else 9f); Sfx.pop(); shakeX = 0.02f; hitAny = true; break }
        }
        if (!hitAny) Sfx.slice()
    }

    private fun shoot(dir: V3, slot: Int) {
        cool[slot] = 0.2f; muz[slot] = 1f; Sfx.pop(); Sfx.slice(); shakeY = 0.01f
        var best: Enemy? = null; var bt = 1e9f; var bl = 0f
        for (e in enemies) { val lh = localHit(e, dir) ?: continue; if (abs(lh[0]) < 0.4f && lh[1] in -0.95f..0.95f && lh[2] < bt) { best = e; bt = lh[2]; bl = lh[1] } }
        best?.let { damage(it, if (bl > 0.5f) 18f else 9f) }
    }

    override fun update(dt: Float, now: Long, hands: List<HandState>, scene: VrScene) {
        time += dt
        val f = scene.head.forward(); headYaw = atan2(f.x, -f.z)
        if (!started) { started = true; reset() }
        for (i in 0..1) { punchT[i] = max(0f, punchT[i] - dt); cool[i] = max(0f, cool[i] - dt); muz[i] = max(0f, muz[i] - dt * 6f) }
        flashRed = max(0f, flashRed - dt * 1.5f); flashWhite = max(0f, flashWhite - dt * 4f)
        shakeX *= 0.85f; shakeY *= 0.85f
        // exit: both palms open for a moment
        if (hands.count { it.palm } >= 2) exitHold += dt else exitHold = 0f
        if (exitHold > 1.2f) { scene.stopWorld(); return }
        for (p in parts.toList()) { p.life -= dt; p.v = V3(p.v.x, p.v.y - 3f * dt, p.v.z); p.p = p.p + p.v * dt; if (p.life <= 0f) parts.remove(p) }
        if (over > 0f) { over -= dt; if (over <= 0f) reset(); return }

        // hands
        for (i in 0..1) aimOn[i] = false
        for (h in hands) {
            val s = h.slot.coerceIn(0, 1)
            if (mode != ZOMBIE) {
                if (h.depthVel > 0.9f && cool[s] <= 0f) { cool[s] = 0.35f; punchT[s] = 0.25f; doPunch(scene.handDir(h, 9)) }
            } else if (h.gun) {
                aim[s] = scene.handDir(h, 8); aimOn[s] = true
                if (h.thumbDown && !wasDown[s] && cool[s] <= 0f) shoot(aim[s], s)
                wasDown[s] = h.thumbDown
            } else wasDown[s] = false
        }
        if (mode == ZOMBIE) { val pd = scene.padPressed; if (pd && !padWas) shoot(f, 0); padWas = pd }

        // enemies
        if (mode == BOX) {
            for (e in enemies.toList()) {
                e.flash = max(0f, e.flash - dt); e.t -= dt
                e.yaw += angDiff(headYaw, e.yaw) * min(1f, dt * 1.5f); e.r += (1.9f - e.r) * min(1f, dt * 2f)
                when (e.state) {
                    0 -> if (e.t <= 0f) { e.state = 1; e.t = max(0.35f, 0.7f - wave * 0.04f); e.side = if (Random.nextBoolean()) 1 else -1; e.yaw0 = headYaw }
                    1 -> if (e.t <= 0f) {
                        e.state = 2; e.t = 0.22f
                        if (abs(angDiff(headYaw, e.yaw0)) > 0.2f) { host.toast("Dodged!"); score += 3 } else hurt(10f + wave * 2f)
                    }
                    2 -> if (e.t <= 0f) { e.state = 4; e.t = 0.5f }
                    else -> if (e.t <= 0f) { e.state = 0; e.t = 0.6f + Random.nextFloat() * max(0.2f, 1.2f - wave * 0.1f) }
                }
            }
        } else {
            spawnT -= dt
            if (spawnT <= 0f && enemies.size < 3 + wave) {
                val e = Enemy(headYaw + (Random.nextFloat() - 0.5f) * 3.6f, 7f)
                e.speed = if (mode == ZOMBIE) 0.55f + 0.06f * wave else 1.2f + 0.08f * wave
                e.hp = if (mode == ZOMBIE) 18f + wave * 2f else 18f
                e.scale = if (mode == GORILLA) 0.85f else 1f
                enemies.add(e); spawnT = max(0.8f, 2.6f - wave * 0.15f)
            }
            for (e in enemies.toList()) {
                e.flash = max(0f, e.flash - dt)
                e.yaw += sin(time * 1.7f + e.phase) * 0.12f * dt
                e.r -= e.speed * dt
                if (e.r < 1.15f) { enemies.remove(e); hurt(if (mode == GORILLA) 14f else 12f) }
            }
        }
    }

    // ------------- drawing -------------
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun drawBoxer(g: VrRenderer.Gfx, e: Enemy) {
        val skin = mix(0xFFD9A07A.toInt(), Color.WHITE, min(1f, e.flash * 3f)); val sw = sin(time * 2 + e.phase) * 0.05f
        g.rect(-0.28f + sw, -0.9f, -0.04f + sw, -0.3f, 0xFF1F2547.toInt()); g.rect(0.04f + sw, -0.9f, 0.28f + sw, -0.3f, 0xFF1F2547.toInt())
        g.rect(-0.32f + sw, -0.38f, 0.32f + sw, -0.02f, 0xFF1D4ED8.toInt())
        g.poly(floatArrayOf(-0.34f + sw, -0.02f, 0.34f + sw, -0.02f, 0.4f + sw, 0.5f, -0.4f + sw, 0.5f), floatArrayOf(-0.02f, -0.02f, 0.5f, 0.5f), skin)
        g.rect(-0.07f + sw, 0.5f, 0.07f + sw, 0.62f, skin)
        g.circle(sw * 1.5f, 0.78f, 0.21f, skin)
        g.circle(-0.08f + sw * 1.5f, 0.82f, 0.03f, 0xFF111111.toInt()); g.circle(0.08f + sw * 1.5f, 0.82f, 0.03f, 0xFF111111.toInt())
        g.rect(-0.06f + sw * 1.5f, 0.7f, 0.06f + sw * 1.5f, 0.72f, 0xFF7A2E2E.toInt())
        g.rect(-0.22f + sw * 1.5f, 0.9f, 0.22f + sw * 1.5f, 0.97f, 0xFF3A2410.toInt())
        // gloves
        val gl = gloveXY(e)
        if (e.state == 1 || e.state == 2) g.line(e.side * 0.4f, 0.45f, gl[0], gl[1], 0.07f, skin)
        val other = if (e.state == 1 || e.state == 2) -e.side * 0.3f else -e.side * 0.3f - sw
        g.circle(other, 0.35f, 0.16f, 0xFFB91C1C.toInt())
        if (e.state == 2) g.ring(gl[0], gl[1], gl[2] * 1.6f, 0xFFFFFFFF.toInt(), 0.7f)
        g.circle(gl[0], gl[1], gl[2], if (e.state == 1) 0xFFFF4040.toInt() else 0xFFDC2626.toInt())
        g.circle(gl[0] - gl[2] * 0.3f, gl[1] + gl[2] * 0.3f, gl[2] * 0.3f, 0xFFFFFFFF.toInt(), 0.35f)
        if (e.state == 1) g.circle(0f, 1.15f, 0.07f, 0xFFFFE14A.toInt())
    }

    private fun drawZombie(g: VrRenderer.Gfx, e: Enemy) {
        val skin = mix(0xFF6E9A5B.toInt(), Color.WHITE, min(1f, e.flash * 3f)); val sw = sin(time * 3 + e.phase) * 0.06f
        g.rect(-0.25f, -0.9f, -0.05f, -0.2f, 0xFF3B3B52.toInt()); g.rect(0.05f, -0.9f, 0.25f, -0.2f, 0xFF3B3B52.toInt())
        g.poly(floatArrayOf(-0.3f, -0.25f, 0.3f, -0.25f, 0.36f, 0.5f, -0.36f, 0.5f), floatArrayOf(-0.25f, -0.25f, 0.5f, 0.5f), 0xFF55657A.toInt())
        g.line(-0.3f, 0.42f, -0.55f, 0.55f + sw, 0.07f, skin); g.line(0.3f, 0.42f, 0.55f, 0.55f - sw, 0.07f, skin)
        g.circle(-0.57f, 0.56f + sw, 0.07f, skin); g.circle(0.57f, 0.56f - sw, 0.07f, skin)
        g.circle(sw, 0.72f, 0.2f, skin)
        g.circle(-0.07f + sw, 0.75f, 0.035f, 0xFFFF2020.toInt()); g.circle(0.07f + sw, 0.75f, 0.035f, 0xFFFF2020.toInt())
        g.rect(-0.07f + sw, 0.63f, 0.07f + sw, 0.66f, 0xFF2A1010.toInt())
        g.rect(-0.1f, 0.1f, 0.0f, 0.3f, 0xFF7A1010.toInt(), 0.6f)
    }

    private fun drawGorilla(g: VrRenderer.Gfx, e: Enemy) {
        val fur = mix(0xFF2B2B33.toInt(), Color.WHITE, min(1f, e.flash * 3f)); val sw = sin(time * 6 + e.phase)
        g.line(-0.25f, -0.5f, -0.3f + sw * 0.1f, -0.9f, 0.12f, fur); g.line(0.25f, -0.5f, 0.3f - sw * 0.1f, -0.9f, 0.12f, fur)
        g.circle(0f, 0.05f, 0.62f, fur)
        g.line(-0.5f, 0.4f, -0.75f, -0.35f + sw * 0.25f, 0.13f, fur); g.line(0.5f, 0.4f, 0.75f, -0.35f - sw * 0.25f, 0.13f, fur)
        g.circle(-0.78f, -0.4f + sw * 0.25f, 0.14f, fur); g.circle(0.78f, -0.4f - sw * 0.25f, 0.14f, fur)
        g.circle(0f, 0.68f, 0.27f, fur)
        g.circle(0f, 0.64f, 0.17f, 0xFF6B5446.toInt())
        g.circle(-0.07f, 0.72f, 0.03f, Color.WHITE); g.circle(0.07f, 0.72f, 0.03f, Color.WHITE)
        g.circle(-0.07f, 0.72f, 0.015f, Color.BLACK); g.circle(0.07f, 0.72f, 0.015f, Color.BLACK)
        g.rect(-0.08f, 0.56f, 0.08f, 0.59f, 0xFF1A1010.toInt())
    }

    override fun draw(g: VrRenderer.Gfx) {
        if (mode == BOX) {
            val e = enemies.firstOrNull()
            val yaw = e?.yaw ?: headYaw
            val p = V3(sin(yaw) * 2.7f, 0f, -cos(yaw) * 2.7f)
            g.place(p, 1f)
            for (y in floatArrayOf(-0.5f, 0f, 0.5f)) g.line(-2.6f, y, 2.6f, y, 0.035f, 0xFFE5E7EB.toInt(), 0.9f)
            g.rect(-2.7f, -1.6f, -2.55f, 0.7f, 0xFF3B82F6.toInt()); g.rect(2.55f, -1.6f, 2.7f, 0.7f, 0xFFEF4444.toInt())
        }
        for (e in enemies.sortedByDescending { it.r }) {
            g.place(e.pos(), e.scale)
            when (mode) { BOX -> drawBoxer(g, e); ZOMBIE -> drawZombie(g, e); else -> drawGorilla(g, e) }
        }
        for (p in parts) { g.place(p.p, 0.03f); g.circle(0f, 0f, 1f, p.col, min(1f, p.life * 2f)) }
        if (mode == ZOMBIE) for (i in 0..1) if (aimOn[i]) { g.place(aim[i] * 5f, 0.12f); g.ring(0f, 0f, 1f, 0xFFFF3B3B.toInt(), 0.9f); g.circle(0f, 0f, 0.1f, 0xFFFF3B3B.toInt()) }
        // HUD (head-locked)
        g.hud()
        g.rect(-0.45f, 0.70f, 0.45f, 0.74f, 0xFF222222.toInt(), 0.6f)
        val ph = (hp / 100f).coerceIn(0f, 1f)
        g.rect(-0.45f, 0.70f, -0.45f + 0.9f * ph, 0.74f, mix(0xFFFF3030.toInt(), 0xFF35E06A.toInt(), ph))
        if (mode == BOX) enemies.firstOrNull()?.let { e ->
            val mh = (60f + wave * 20f); val eh = (e.hp / mh).coerceIn(0f, 1f)
            g.rect(-0.45f, 0.64f, 0.45f, 0.67f, 0xFF222222.toInt(), 0.6f); g.rect(-0.45f, 0.64f, -0.45f + 0.9f * eh, 0.67f, 0xFFFFB020.toInt())
        }
        for (i in 0 until min(wave, 10)) g.circle(-0.27f + i * 0.06f, 0.79f, 0.015f, 0xFFFFFFFF.toInt())
        if (exitHold > 0.1f) g.circle(0f, 0f, 0.15f * (exitHold / 1.2f), 0xFFFFFFFF.toInt(), 0.5f)
        if (flashRed > 0f) g.screen(0xFFFF0000.toInt(), flashRed * 0.45f)
        if (flashWhite > 0f) g.screen(0xFFFFFFFF.toInt(), flashWhite)
        if (over > 0f) g.screen(0xFF000000.toInt(), 0.5f)
    }
}
