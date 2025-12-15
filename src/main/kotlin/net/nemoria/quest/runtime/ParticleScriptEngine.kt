package net.nemoria.quest.runtime

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ParticleScriptEngine(
    private val plugin: JavaPlugin
) {
    enum class Mode { ONCE, RESTART }

    data class Handle internal constructor(internal val id: Long)

    private sealed interface Program {
        fun newRun(viewerId: java.util.UUID, locationProvider: () -> Location?, mode: Mode): Run
    }

    private data class TextProgram(val statements: List<Stmt>) : Program {
        override fun newRun(viewerId: java.util.UUID, locationProvider: () -> Location?, mode: Mode): Run =
            Run(viewerId = viewerId, locationProvider = locationProvider, mode = mode, root = statements)
    }

    private data class YamlProgram(
        val particle: Particle,
        val count: Int,
        val offsetX: Double,
        val offsetY: Double,
        val offsetZ: Double,
        val spreadX: Double,
        val spreadY: Double,
        val spreadZ: Double,
        val speed: Double,
        val interval: Long,
        val duration: Long
    ) : Program {
        override fun newRun(viewerId: java.util.UUID, locationProvider: () -> Location?, mode: Mode): Run {
            val effectiveInterval = interval.coerceAtLeast(1L)
            val effectiveDuration = duration.coerceAtLeast(0L)
            val statements = listOf(
                LoopTicksStmt(
                    intervalTicks = effectiveInterval,
                    totalTicks = effectiveDuration,
                    particle = particle,
                    count = count,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    offsetZ = offsetZ,
                    spreadX = spreadX,
                    spreadY = spreadY,
                    spreadZ = spreadZ,
                    speed = speed
                )
            )
            return Run(viewerId = viewerId, locationProvider = locationProvider, mode = mode, root = statements)
        }
    }

    private sealed interface Stmt
    private data class SetStmt(val variable: String, val expr: Expr) : Stmt
    private data class WaitStmt(val ticksExpr: Expr) : Stmt
    private data class DisplayStmt(
        val particleName: String,
        val x: Expr,
        val y: Expr,
        val z: Expr,
        val count: Expr,
        val extras: List<Expr>
    ) : Stmt

    private data class LoopStmt(
        val variable: String,
        val begin: Expr,
        val end: Expr,
        val step: Expr,
        val body: List<Stmt>
    ) : Stmt

    private data class LoopTicksStmt(
        val intervalTicks: Long,
        val totalTicks: Long,
        val particle: Particle,
        val count: Int,
        val offsetX: Double,
        val offsetY: Double,
        val offsetZ: Double,
        val spreadX: Double,
        val spreadY: Double,
        val spreadZ: Double,
        val speed: Double
    ) : Stmt

    private sealed interface Expr {
        fun eval(vars: Map<String, Double>): Double
    }

    private data class RpnExpr(private val tokens: List<Token>) : Expr {
        override fun eval(vars: Map<String, Double>): Double {
            val stack = ArrayDeque<Double>()
            for (t in tokens) {
                when (t) {
                    is Token.Num -> stack.addLast(t.v)
                    is Token.Var -> stack.addLast(vars[t.name] ?: 0.0)
                    is Token.Op -> {
                        val b = stack.removeLastOrNull() ?: 0.0
                        val a = stack.removeLastOrNull() ?: 0.0
                        stack.addLast(
                            when (t.kind) {
                                OpKind.ADD -> a + b
                                OpKind.SUB -> a - b
                                OpKind.MUL -> a * b
                                OpKind.DIV -> if (b == 0.0) 0.0 else a / b
                            }
                        )
                    }
                    is Token.Func -> {
                        val a = stack.removeLastOrNull() ?: 0.0
                        val radians = a * (PI / 180.0)
                        stack.addLast(
                            when (t.name.lowercase()) {
                                "sin" -> sin(radians)
                                "cos" -> cos(radians)
                                else -> 0.0
                            }
                        )
                    }
                }
            }
            return stack.lastOrNull() ?: 0.0
        }
    }

    private sealed interface Token {
        data class Num(val v: Double) : Token
        data class Var(val name: String) : Token
        data class Op(val kind: OpKind) : Token
        data class Func(val name: String) : Token
    }

    private enum class OpKind(val prec: Int) { ADD(1), SUB(1), MUL(2), DIV(2) }

    private data class Frame(
        val statements: List<Stmt>,
        var idx: Int,
        val loop: LoopFrame?
    )

    private data class LoopFrame(
        val variable: String,
        var current: Double,
        val end: Double,
        val step: Double
    )

    private data class Run(
        val viewerId: java.util.UUID,
        val locationProvider: () -> Location?,
        val mode: Mode,
        val root: List<Stmt>
    ) {
        val vars: MutableMap<String, Double> = HashMap()
        val stack: ArrayDeque<Frame> = ArrayDeque()
        var waitingTicks: Long = 0L

        fun reset() {
            vars.clear()
            stack.clear()
            stack.addLast(Frame(root, 0, null))
            waitingTicks = 0L
        }
    }

    private val programs: MutableMap<String, Program> = ConcurrentHashMap()
    private val runs: MutableMap<Long, Run> = ConcurrentHashMap()
    private val nextRunId = AtomicLong(1L)
    private var tickTask: org.bukkit.scheduler.BukkitTask? = null

    fun cachedProgramsCount(): Int = programs.size
    fun isActive(handle: Handle): Boolean = runs.containsKey(handle.id)

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        runs.clear()
        programs.clear()
    }

    fun preload(dir: File) {
        programs.clear()
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { f -> f.isFile }?.forEach { file ->
            val id = file.nameWithoutExtension
            loadProgram(id)
        }
    }

    fun start(
        viewer: Player,
        scriptId: String,
        locationProvider: () -> Location?,
        mode: Mode,
        runImmediately: Boolean = true
    ): Handle? {
        val id = scriptId.trim()
        if (id.isEmpty()) return null
        val program = loadProgram(id)
        if (program == null) {
            val particle = runCatching { Particle.valueOf(id.uppercase()) }.getOrNull() ?: return null
            val loc = locationProvider() ?: return null
            if (viewer.world != loc.world) return null
            viewer.spawnParticle(particle, loc, 10, 0.0, 0.0, 0.0, 0.0)
            return null
        }

        ensureTickTask()
        val run = program.newRun(viewer.uniqueId, locationProvider, mode).also { it.reset() }
        val runId = nextRunId.getAndIncrement()
        runs[runId] = run
        if (runImmediately) tick(runId, run, budget = 256)
        return Handle(runId)
    }

    fun cancel(handle: Handle) {
        runs.remove(handle.id)
    }

    private fun ensureTickTask() {
        if (tickTask != null) return
        tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tickAll() }, 1L, 1L)
    }

    private fun tickAll() {
        if (runs.isEmpty()) {
            tickTask?.cancel()
            tickTask = null
            return
        }
        val it = runs.entries.iterator()
        var globalBudget = 10_000
        while (it.hasNext() && globalBudget > 0) {
            val (id, run) = it.next()
            val used = tick(id, run, budget = minOf(512, globalBudget))
            globalBudget -= used
            if (!runs.containsKey(id)) {
                // already removed by tick()
            }
        }
        if (runs.isEmpty()) {
            tickTask?.cancel()
            tickTask = null
        }
    }

    private fun tick(runId: Long, run: Run, budget: Int): Int {
        val viewer = plugin.server.getPlayer(run.viewerId)
        if (viewer == null || !viewer.isOnline) {
            runs.remove(runId)
            return 0
        }
        val base = run.locationProvider()
        if (base == null || base.world == null || viewer.world != base.world) {
            runs.remove(runId)
            return 0
        }

        run.vars["baseX"] = base.x
        run.vars["baseY"] = base.y
        run.vars["baseZ"] = base.z

        if (run.waitingTicks > 0L) {
            run.waitingTicks -= 1L
            if (run.waitingTicks > 0L) return 0
        }

        var used = 0
        while (used < budget) {
            val top = run.stack.lastOrNull()
            if (top == null) {
                if (run.mode == Mode.RESTART) {
                    run.reset()
                    continue
                }
                runs.remove(runId)
                return used
            }

            if (top.idx >= top.statements.size) {
                val loop = top.loop
                if (loop != null) {
                    val next = loop.current + loop.step
                    loop.current = next
                    val inRange = if (loop.step >= 0.0) next <= loop.end else next >= loop.end
                    if (inRange) {
                        run.vars[loop.variable] = next
                        top.idx = 0
                        continue
                    }
                }
                run.stack.removeLast()
                continue
            }

            val stmt = top.statements[top.idx]
            top.idx += 1
            used += 1

            when (stmt) {
                is SetStmt -> run.vars[stmt.variable] = stmt.expr.eval(run.vars)
                is WaitStmt -> {
                    run.waitingTicks = stmt.ticksExpr.eval(run.vars).toLong().coerceAtLeast(0L)
                    return used
                }
                is DisplayStmt -> {
                    val particle = runCatching { Particle.valueOf(stmt.particleName.uppercase()) }.getOrNull() ?: continue
                    val x = stmt.x.eval(run.vars)
                    val y = stmt.y.eval(run.vars)
                    val z = stmt.z.eval(run.vars)
                    val cnt = stmt.count.eval(run.vars).toInt().coerceAtLeast(0)
                    val loc = Location(base.world, x, y, z)

                    when (stmt.extras.size) {
                        0 -> viewer.spawnParticle(particle, loc, cnt, 0.0, 0.0, 0.0, 0.0)
                        1 -> {
                            val noteColor = stmt.extras[0].eval(run.vars).toInt().coerceIn(0, 24)
                            if (particle == Particle.NOTE) {
                                val offX = noteColor / 24.0
                                viewer.spawnParticle(Particle.NOTE, loc, 0, offX, 0.0, 0.0, 1.0)
                            } else {
                                viewer.spawnParticle(particle, loc, cnt, 0.0, 0.0, 0.0, 0.0)
                            }
                        }
                        else -> {
                            val rRaw = stmt.extras.getOrNull(0)?.eval(run.vars) ?: 0.0
                            val gRaw = stmt.extras.getOrNull(1)?.eval(run.vars) ?: 0.0
                            val bRaw = stmt.extras.getOrNull(2)?.eval(run.vars) ?: 0.0
                            val r = toRgbByte(rRaw)
                            val g = toRgbByte(gRaw)
                            val b = toRgbByte(bRaw)
                            val dust = Particle.DustOptions(Color.fromRGB(r, g, b), 1f)
                            if (particle == Particle.DUST) {
                                viewer.spawnParticle(particle, loc, cnt, 0.0, 0.0, 0.0, 0.0, dust)
                            } else {
                                viewer.spawnParticle(particle, loc, cnt, 0.0, 0.0, 0.0, 0.0)
                            }
                        }
                    }
                }
                is LoopStmt -> {
                    val begin = stmt.begin.eval(run.vars)
                    val end = stmt.end.eval(run.vars)
                    val step = stmt.step.eval(run.vars)
                    if (step == 0.0) continue
                    val inRange = if (step >= 0.0) begin <= end else begin >= end
                    if (!inRange) continue
                    run.vars[stmt.variable] = begin
                    run.stack.addLast(Frame(stmt.body, 0, LoopFrame(stmt.variable, begin, end, step)))
                }
                is LoopTicksStmt -> {
                    val interval = stmt.intervalTicks.coerceAtLeast(1L)
                    val maxTicks = stmt.totalTicks.coerceAtLeast(0L)
                    val done = (run.vars["__yamlTicks"] ?: 0.0).toLong()
                    if (maxTicks > 0 && done >= maxTicks) {
                        run.vars.remove("__yamlTicks")
                        continue
                    }
                    val loc = base.clone().add(stmt.offsetX, stmt.offsetY, stmt.offsetZ)
                    viewer.spawnParticle(stmt.particle, loc, stmt.count, stmt.spreadX, stmt.spreadY, stmt.spreadZ, stmt.speed)
                    run.vars["__yamlTicks"] = (done + interval).toDouble()
                    run.waitingTicks = interval
                    return used
                }
            }
        }
        return used
    }

    private fun toRgbByte(v: Double): Int {
        val normalized = if (v <= 1.0) (v * 255.0) else v
        return normalized.toInt().coerceIn(0, 255)
    }

    private fun loadProgram(id: String): Program? {
        programs[id]?.let { return it }
        val dir = File(plugin.dataFolder, "content/particle_scripts")
        val txt = File(dir, "$id.txt")
        if (txt.exists()) {
            val parsed = parseTextProgram(runCatching { txt.readText() }.getOrNull() ?: return null)
            if (parsed != null) {
                programs[id] = parsed
                return parsed
            }
        }
        val yml = listOf(File(dir, "$id.yml"), File(dir, "$id.yaml")).firstOrNull { it.exists() }
        if (yml != null) {
            val cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(yml)
            val particle = runCatching { Particle.valueOf(cfg.getString("particle")?.uppercase() ?: "") }.getOrNull()
                ?: return null
            val program = YamlProgram(
                particle = particle,
                count = cfg.getInt("count", 10),
                offsetX = cfg.getDouble("offset.x", 0.0),
                offsetY = cfg.getDouble("offset.y", 0.0),
                offsetZ = cfg.getDouble("offset.z", 0.0),
                spreadX = cfg.getDouble("spread.x", 0.0),
                spreadY = cfg.getDouble("spread.y", 0.0),
                spreadZ = cfg.getDouble("spread.z", 0.0),
                speed = cfg.getDouble("speed", 0.0),
                interval = cfg.getLong("interval", 5L),
                duration = cfg.getLong("duration", 40L)
            )
            programs[id] = program
            return program
        }
        return null
    }

    private fun parseTextProgram(text: String): TextProgram? {
        val lines = text.lines()
        val root = mutableListOf<Stmt>()
        val stack = ArrayDeque<Pair<Int, MutableList<Stmt>>>()
        stack.addLast(0 to root)
        var lastLoop: LoopStmt? = null

        fun currentIndent(): Int = stack.last().first
        fun currentList(): MutableList<Stmt> = stack.last().second

        for (raw in lines) {
            val withoutComment = raw.substringBefore("#")
            if (withoutComment.isBlank()) continue
            val indent = withoutComment.takeWhile { it == ' ' || it == '\t' }.length
            val line = withoutComment.trim()
            if (line.isEmpty()) continue

            while (indent < currentIndent()) {
                stack.removeLast()
            }
            if (indent > currentIndent()) {
                val loop = lastLoop ?: return null
                val body = mutableListOf<Stmt>()
                currentList().removeLast()
                val rebuilt = loop.copy(body = body)
                currentList().add(rebuilt)
                stack.addLast(indent to body)
                lastLoop = null
            } else {
                lastLoop = null
            }

            val stmt = parseStmt(line) ?: return null
            currentList().add(stmt)
            if (stmt is LoopStmt) lastLoop = stmt
        }

        return TextProgram(root)
    }

    private fun parseStmt(line: String): Stmt? {
        val name = line.takeWhile { it.isLetterOrDigit() || it == '_' }
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (name.isBlank() || open < 0 || close < open) return null
        val argsRaw = line.substring(open + 1, close)
        val args = splitArgs(argsRaw)
        return when (name.lowercase()) {
            "set" -> {
                val varName = args.getOrNull(0)?.trim().orEmpty()
                val expr = args.getOrNull(1)?.let { parseExpr(it) } ?: return null
                if (varName.isBlank()) return null
                SetStmt(varName, expr)
            }
            "wait", "wait_ticks" -> {
                val expr = args.getOrNull(0)?.let { parseExpr(it) } ?: return null
                WaitStmt(expr)
            }
            "display" -> {
                val particleName = args.getOrNull(0)?.trim().orEmpty()
                val x = args.getOrNull(1)?.let { parseExpr(it) } ?: return null
                val y = args.getOrNull(2)?.let { parseExpr(it) } ?: return null
                val z = args.getOrNull(3)?.let { parseExpr(it) } ?: return null
                val count = args.getOrNull(4)?.let { parseExpr(it) } ?: return null
                val extras = args.drop(5).map { parseExpr(it) ?: return null }
                if (particleName.isBlank()) return null
                DisplayStmt(particleName, x, y, z, count, extras)
            }
            "loop" -> {
                val varName = args.getOrNull(0)?.trim().orEmpty()
                val begin = args.getOrNull(1)?.let { parseExpr(it) } ?: return null
                val end = args.getOrNull(2)?.let { parseExpr(it) } ?: return null
                val step = args.getOrNull(3)?.let { parseExpr(it) } ?: return null
                if (varName.isBlank()) return null
                LoopStmt(varName, begin, end, step, body = emptyList())
            }
            else -> null
        }
    }

    private fun splitArgs(raw: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in raw) {
            when (c) {
                '(' -> {
                    depth++
                    sb.append(c)
                }
                ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append(c)
                }
                ',' -> if (depth == 0) {
                    out.add(sb.toString().trim())
                    sb.clear()
                } else sb.append(c)
                else -> sb.append(c)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun parseExpr(raw: String): Expr? {
        val tokens = tokenize(raw) ?: return null
        val output = mutableListOf<Token>()
        val ops = ArrayDeque<Any>() // Token.Op or Token.Func or '('

        fun popOps(minPrec: Int) {
            while (true) {
                val top = ops.lastOrNull() ?: break
                if (top is Token.Op && top.kind.prec >= minPrec) {
                    output.add(ops.removeLast() as Token.Op)
                } else {
                    break
                }
            }
        }

        for (t in tokens) {
            when (t) {
                is Lex.Num -> output.add(Token.Num(t.v))
                is Lex.Ident -> {
                    if (t.isFunc) {
                        ops.addLast(Token.Func(t.name))
                    } else {
                        output.add(Token.Var(t.name))
                    }
                }
                is Lex.Op -> {
                    val kind = when (t.op) {
                        "+" -> OpKind.ADD
                        "-" -> OpKind.SUB
                        "*" -> OpKind.MUL
                        "/" -> OpKind.DIV
                        else -> return null
                    }
                    popOps(kind.prec)
                    ops.addLast(Token.Op(kind))
                }
                Lex.LParen -> ops.addLast('(')
                Lex.RParen -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        val op = ops.removeLast()
                        if (op is Token.Op) output.add(op)
                        if (op is Token.Func) output.add(op)
                    }
                    if (ops.isNotEmpty() && ops.last() == '(') ops.removeLast()
                    if (ops.lastOrNull() is Token.Func) output.add(ops.removeLast() as Token.Func)
                }
            }
        }
        while (ops.isNotEmpty()) {
            val op = ops.removeLast()
            if (op is Token.Op) output.add(op)
            if (op is Token.Func) output.add(op)
        }
        return RpnExpr(output)
    }

    private sealed interface Lex {
        data class Num(val v: Double) : Lex
        data class Ident(val name: String, val isFunc: Boolean) : Lex
        data class Op(val op: String) : Lex
        data object LParen : Lex
        data object RParen : Lex
    }

    private fun tokenize(raw: String): List<Lex>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val out = mutableListOf<Lex>()
        var i = 0
        var lastWasOpOrLParen = true
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> {
                    out.add(Lex.LParen); i++; lastWasOpOrLParen = true
                }
                c == ')' -> {
                    out.add(Lex.RParen); i++; lastWasOpOrLParen = false
                }
                c == '+' || c == '*' || c == '/' -> {
                    out.add(Lex.Op(c.toString())); i++; lastWasOpOrLParen = true
                }
                c == '-' -> {
                    if (lastWasOpOrLParen) {
                        // unary minus -> treat as 0 - x
                        out.add(Lex.Num(0.0))
                        out.add(Lex.Op("-"))
                        i++
                        lastWasOpOrLParen = true
                    } else {
                        out.add(Lex.Op("-")); i++; lastWasOpOrLParen = true
                    }
                }
                c.isDigit() || c == '.' -> {
                    val j = i
                    var k = i
                    while (k < s.length && (s[k].isDigit() || s[k] == '.')) k++
                    val num = s.substring(j, k).toDoubleOrNull() ?: return null
                    out.add(Lex.Num(num))
                    i = k
                    lastWasOpOrLParen = false
                }
                c.isLetter() || c == '_' -> {
                    val j = i
                    var k = i
                    while (k < s.length && (s[k].isLetterOrDigit() || s[k] == '_' )) k++
                    val name = s.substring(j, k)
                    var isFunc = false
                    var kk = k
                    while (kk < s.length && s[kk].isWhitespace()) kk++
                    if (kk < s.length && s[kk] == '(') isFunc = true
                    out.add(Lex.Ident(name, isFunc))
                    i = k
                    lastWasOpOrLParen = false
                }
                else -> return null
            }
        }
        return out
    }
}
