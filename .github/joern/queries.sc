// CodeBadger / Joern CPG analysis queries for Kompressor
// Detects resource leaks, unsafe patterns, and domain-specific issues
// across Kotlin (Android) and Swift (iOS) source sets.
//
// Suppression: place // codebadger:suppress(<ruleId>) on (or above) flagged line.

@main def main(cpgFile: String, outputFile: String, configFile: String = ".codebadger.json"): Unit = {
  importCpg(cpgFile)

  import io.shiftleft.codepropertygraph.generated.nodes._
  import io.shiftleft.semanticcpg.language._
  import ujson._

  // ── Load configuration ──────────────────────────────────────
  val config: ujson.Value = {
    val path = os.Path(configFile, os.pwd)
    if (os.exists(path)) ujson.read(os.read(path)) else ujson.Obj()
  }

  val excludePatterns: List[String] =
    config.obj.get("exclude").map(_.arr.map(_.str).toList).getOrElse(Nil)

  def ruleEnabled(ruleId: String): Boolean =
    config.obj.get("rules").flatMap(_.obj.get(ruleId)).flatMap(_.obj.get("enabled")).forall(_.bool)

  // ── Helpers ─────────────────────────────────────────────────
  val findings = scala.collection.mutable.ArrayBuffer[ujson.Obj]()

  def finding(ruleId: String, severity: String, message: String, file: String, line: Int, method: String): Unit = {
    if (excludePatterns.exists(p => file.matches(p.replace("**", ".*").replace("*", "[^/]*")))) return
    val suppressed = isSuppressed(file, line, ruleId)
    findings += ujson.Obj(
      "type" -> ruleId,
      "severity" -> severity,
      "message" -> message,
      "file" -> file,
      "line" -> line,
      "method" -> method,
      "suppressed" -> suppressed,
    )
  }

  def isSuppressed(filePath: String, lineNumber: Int, ruleId: String): Boolean = {
    val path = os.Path(filePath, os.pwd)
    if (!os.exists(path)) return false
    try {
      val lines = os.read.lines(path)
      val targetLine = lineNumber - 1 // 0-indexed
      val linesToCheck = List(targetLine, targetLine - 1).filter(i => i >= 0 && i < lines.length)
      linesToCheck.exists { i =>
        lines(i).contains(s"codebadger:suppress($ruleId)") ||
        lines(i).contains("codebadger:suppress(all)")
      }
    } catch {
      case _: Exception => false
    }
  }

  def fileOf(node: StoredNode): String =
    node match {
      case n: HasFile => n.file.name.headOption.getOrElse("unknown")
      case _ => "unknown"
    }

  def lineOf(node: StoredNode): Int =
    node match {
      case n: HasLineNumber => n.lineNumber.getOrElse(-1)
      case _ => -1
    }

  // ── Q1: Resource leak — not closed in finally ───────────────
  if (ruleEnabled("resource-leak")) {
    val resourceTypes = List(
      "InputStream", "OutputStream", "FileInputStream", "FileOutputStream",
      "ByteArrayOutputStream", "BufferedInputStream", "BufferedOutputStream",
      "MediaCodec", "MediaMuxer", "MediaExtractor",
      "BitmapFactory", "Bitmap",
      "AVAssetExportSession", "AVAssetReader", "AVAssetWriter",
    )

    for (t <- resourceTypes) {
      val opens = cpg.call
        .name(".*(?i)(open|create|init|decode|start|setDataSource).*")
        .where(_.or(_.typeFullName(s".*$t.*"), _.code(s".*$t.*")))
        .l

      for (open <- opens) {
        val method = open.method
        // Check for close/release/recycle calls inside finally blocks
        val closesInFinally = method.ast.isControlStructure
          .controlStructureType("FINALLY")
          .ast.isCall
          .name(".*(?i)(close|release|stop|finish|recycle|safeRelease|safeStopAndRelease).*")
          .l
        // Also accept .use { } blocks as valid resource management
        val useBlocks = method.call.name("use").l
        if (closesInFinally.isEmpty && useBlocks.isEmpty) {
          // Fallback: check if ANY close exists (weaker but avoids false positives
          // for simple cases where finally is not syntactically required)
          val anyClose = method.call
            .name(".*(?i)(close|release|stop|finish|recycle|safeRelease|safeStopAndRelease).*")
            .l
          if (anyClose.isEmpty) {
            finding(
              "resource-leak", "warning",
              s"Potential resource leak: $t opened but not closed/released in finally block",
              fileOf(open), lineOf(open), method.name,
            )
          }
        }
      }
    }
  }

  // ── Q2: MediaCodec bare release() ───────────────────────────
  if (ruleEnabled("mediacodec-safe-release")) {
    val bareReleases = cpg.call
      .name("release|stop")
      .where(_.or(
        _.argument(0).typeFullName(".*MediaCodec.*"),
        _.argument(0).typeFullName(".*MediaMuxer.*"),
        _.code(".*MediaCodec.*"),
        _.code(".*MediaMuxer.*"),
      ))
      .l

    for (call <- bareReleases) {
      val methodName = call.method.name
      // Exclude the safeRelease/safeStopAndRelease helper definitions themselves
      if (methodName != "safeRelease" && methodName != "safeStopAndRelease") {
        finding(
          "mediacodec-safe-release", "warning",
          s"Use safeRelease()/safeStopAndRelease() instead of bare ${call.name}() on MediaCodec/MediaMuxer",
          fileOf(call), lineOf(call), methodName,
        )
      }
    }
  }

  // ── Q3: CFRelease missing for retained CF objects ───────────
  if (ruleEnabled("cfrelease-missing")) {
    val retainedCalls = cpg.call
      .name(".*(?i)(copyNextSampleBuffer|CGBitmapContextCreate|CGImageCreate).*")
      .l

    for (call <- retainedCalls) {
      val method = call.method
      val cfReleaseInFinally = method.ast.isControlStructure
        .controlStructureType("FINALLY")
        .ast.isCall
        .name(".*(?i)(CFRelease).*")
        .l
      // Also accept CFRelease anywhere in a try block (covers try-finally patterns
      // where Joern may model the structure differently for Kotlin/Native)
      val cfReleaseAnywhere = method.call.name(".*(?i)(CFRelease).*").l
      if (cfReleaseAnywhere.isEmpty) {
        finding(
          "cfrelease-missing", "error",
          s"Retained CF object from ${call.name}() must be released with CFRelease in a finally block",
          fileOf(call), lineOf(call), method.name,
        )
      }
    }
  }

  // ── Q4: UIGraphics context pairing ──────────────────────────
  if (ruleEnabled("uigraphics-context-pairing")) {
    val begins = cpg.call
      .name(".*UIGraphicsBeginImageContext.*")
      .l

    for (begin <- begins) {
      val method = begin.method
      val endsInFinally = method.ast.isControlStructure
        .controlStructureType("FINALLY")
        .ast.isCall
        .name(".*UIGraphicsEndImageContext.*")
        .l
      if (endsInFinally.isEmpty) {
        // Check if End exists at all (fallback for Kotlin/Native CPG modeling)
        val anyEnd = method.call.name(".*UIGraphicsEndImageContext.*").l
        if (anyEnd.isEmpty) {
          finding(
            "uigraphics-context-pairing", "error",
            "UIGraphicsBeginImageContext without matching UIGraphicsEndImageContext in finally block",
            fileOf(begin), lineOf(begin), method.name,
          )
        }
      }
    }
  }

  // ── Q5: Cancellation checkpoint missing in loops ────────────
  if (ruleEnabled("cancellation-checkpoint-missing")) {
    // Find loops (while/for/do) that are inside methods with coroutine markers
    val coroutineMethods = cpg.method
      .where(_.call.name(".*(?i)(ensureActive|currentCoroutineContext|yield|suspendCancellableCoroutine).*"))
      .l

    for (method <- coroutineMethods) {
      val loops = method.ast.isControlStructure
        .controlStructureType("WHILE|FOR|DO")
        .l

      for (loop <- loops) {
        val checkpoints = loop.ast.isCall
          .name(".*(?i)(ensureActive|yield).*")
          .l
        if (checkpoints.isEmpty) {
          finding(
            "cancellation-checkpoint-missing", "warning",
            s"Loop in coroutine context lacks ensureActive()/yield() cancellation checkpoint",
            fileOf(loop), lineOf(loop), method.name,
          )
        }
      }
    }
  }

  // ── Q6: Bare runCatching (should be suspendRunCatching) ─────
  if (ruleEnabled("bare-runcatching")) {
    val bareRunCatching = cpg.call
      .name("runCatching")
      .filterNot(_.file.name(".*(?i)test.*"))
      .filterNot(_.file.name(".*SuspendRunCatching.*"))
      .l

    for (call <- bareRunCatching) {
      finding(
        "bare-runcatching", "error",
        "Use suspendRunCatching instead of runCatching to preserve structured concurrency (CancellationException)",
        fileOf(call), lineOf(call), call.method.name,
      )
    }
  }

  // ── Q7: Incomplete continuation handlers ────────────────────
  if (ruleEnabled("continuation-incomplete")) {
    val continuations = cpg.call
      .name("suspendCancellableCoroutine")
      .l

    for (cont <- continuations) {
      val method = cont.method
      // Check for the three required patterns in the method containing the continuation
      val hasResume = method.call.name("resume").nonEmpty
      val hasResumeWithException = method.call.name("resumeWithException").nonEmpty
      val hasInvokeOnCancellation = method.call.name("invokeOnCancellation").nonEmpty

      if (!hasResume || !hasResumeWithException || !hasInvokeOnCancellation) {
        val missing = List(
          if (!hasResume) "resume" else "",
          if (!hasResumeWithException) "resumeWithException" else "",
          if (!hasInvokeOnCancellation) "invokeOnCancellation" else "",
        ).filter(_.nonEmpty).mkString(", ")
        finding(
          "continuation-incomplete", "warning",
          s"suspendCancellableCoroutine missing handler(s): $missing",
          fileOf(cont), lineOf(cont), method.name,
        )
      }
    }
  }

  // ── Q8: Allocation in compression loops ─────────────────────
  if (ruleEnabled("allocation-in-loop")) {
    val allocsInLoops = cpg.method
      .name(".*(?i)(compress|encode|transcode|remux|copySamples).*")
      .ast.isCall
      .name(".*(?i)(ByteArray|allocate|NSMutableData|createBitmap|createScaledBitmap).*")
      .where(_.repeat(_.astParent)(_.until(_.isControlStructure.controlStructureType("FOR|WHILE|DO"))))
      .l

    for (alloc <- allocsInLoops) {
      finding(
        "allocation-in-loop", "warning",
        s"Buffer allocation inside loop in media method: ${alloc.code.take(80)}",
        fileOf(alloc), lineOf(alloc), alloc.method.name,
      )
    }
  }

  // ── Q9: Taint flow — user input to file write ───────────────
  if (ruleEnabled("taint-flow")) {
    val sources = cpg.call
      .name(".*(?i)(getParameter|readLine|userInput|intent\\.get|Uri\\.parse|openInputStream).*")
      .l
    val sinks = cpg.call
      .name(".*(?i)(write|outputStream|saveTo).*")
      .l

    for (source <- sources; sink <- sinks) {
      val flows = sink.reachableByFlows(source).l
      for (flow <- flows) {
        finding(
          "taint-flow", "error",
          s"Unvalidated data flow from ${source.code.take(60)} to ${sink.code.take(60)}",
          fileOf(source), lineOf(source), source.method.name,
        )
      }
    }
  }

  // ── Q10: Force-unwrap detection (Swift) ─────────────────────
  if (ruleEnabled("force-unwrap")) {
    val forceUnwraps = cpg.call.name(".*!$").l
    for (fu <- forceUnwraps) {
      finding(
        "force-unwrap", "warning",
        s"Force unwrap detected: ${fu.code.take(80)}",
        fileOf(fu), lineOf(fu), fu.method.name,
      )
    }
  }

  // ── Write results and exit ──────────────────────────────────
  val json = ujson.write(findings.toList, indent = 2)
  os.write.over(os.Path(outputFile, os.pwd), json)

  val unsuppressed = findings.count(!_("suppressed").bool)
  val suppressed = findings.count(_("suppressed").bool)
  println(s"CodeBadger: ${findings.size} finding(s) total, $unsuppressed active, $suppressed suppressed → $outputFile")

  if (unsuppressed > 0) {
    System.err.println(s"FAIL: $unsuppressed unsuppressed finding(s)")
    sys.exit(1)
  }
}
