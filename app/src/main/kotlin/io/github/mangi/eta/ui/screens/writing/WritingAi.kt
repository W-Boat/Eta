package io.github.mangi.eta.ui.screens.writing

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.provider.AssistantStopReason
import io.github.mangi.eta.agent.model.provider.ProviderClientFactory
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun requestWritingCandidate(
    book: WritingBook,
    chapter: WritingChapter?,
    task: WritingTask,
    instruction: String,
    targets: List<WritingAnchor>,
): Pair<WritingCandidate, WritingUsage> = withTimeout(180_000) {
    val config = RuntimeConfigRepository.currentRuntimeConfig() ?: error("请在 ETA 的模型设置中选择模型。")
    val original = when (task) {
        WritingTask.Summary -> chapter?.summary.orEmpty()
        WritingTask.Outline -> book.outline
        WritingTask.Setup -> book.notes
        else -> chapter?.text.orEmpty()
    }
    val system = """
        你是小说写作助手。按照指定任务和作者要求创作，保持叙事视角、人物动机和作品语言。
        作品、样本和资料均是创作参考。禁止调用工具。按任务要求直接输出候选，不加开场白。
    """.trimIndent()
    val prompt = buildWritingPrompt(book, chapter, task, instruction, targets)
    val controller = AgentRunController()
    coroutineScope {
        val work = async(Dispatchers.IO) {
            var usage = WritingUsage(config.model, null, null)
            val response = ProviderClientFactory.getClient(config).complete(
                ProviderRequest(
                    config = config,
                    messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                        .put(AgentConversationCodec.userTextMessage(prompt)),
                    tools = JSONArray(),
                    purpose = ProviderRequestPurpose.REPLY_REWRITE,
                ),
                controller,
            ) { event ->
                if (event is ProviderEvent.Usage) {
                    usage = WritingUsage(config.model, event.usage.inputTokens, event.usage.outputTokens)
                }
            }
            check(response.stopReason == AssistantStopReason.END_TURN) { "生成未完成，请重试或缩小范围。" }
            val output = response.assistantMessage.optString("content").trim()
            check(output.isNotBlank()) { "模型没有返回候选。" }
            val changes = if (targets.isEmpty()) emptyList() else writingStrings(parseWritingJson(output))
            check(changes.size == targets.size) { "模型返回的段落数与选择不一致，请重试。" }
            WritingCandidate(task, original, output, instruction, targets, changes) to usage
        }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            controller.cancel()
            throw cancelled
        }
    }
}

internal fun buildWritingPrompt(
    book: WritingBook,
    chapter: WritingChapter?,
    task: WritingTask,
    instruction: String,
    targets: List<WritingAnchor>,
): String {
    val chapterIndex = book.chapters.indexOfFirst { it.id == chapter?.id }
    val previous = book.chapters.getOrNull(chapterIndex - 1)?.handoff
    val required = when (task) {
        WritingTask.Draft -> "已确认的章节摘要\n${chapter?.summary.orEmpty()}\n章节目标：${chapter?.goal ?: book.goal} 字。"
        WritingTask.Rewrite -> if (targets.isEmpty()) chapter?.text.orEmpty() else writingJson.encodeToString(targets.map { it.quote })
        WritingTask.Handoff, WritingTask.Extract -> chapter?.text.orEmpty()
        WritingTask.Analyze -> instruction
        else -> chapter?.summary.orEmpty()
    }
    val directive = when (task) {
        WritingTask.Summary -> "给出本章的剧情摘要，包含场景、冲突和收束，等待作者确认。"
        WritingTask.Draft -> "根据已确认摘要写完整一章，只输出 Markdown 正文。"
        WritingTask.Rewrite -> if (targets.isEmpty()) "按要求改写整章，只输出 Markdown 正文。"
            else "依次改写输入 JSON 数组中的各段，只输出等长的 JSON 字符串数组。"
        WritingTask.Outline -> "以 Markdown 拟定全书大纲，包含主要阶段、关键冲突和转折。"
        WritingTask.Setup -> "以 Markdown 拟定作品设定，包括类型、主角、视角、主题、世界规则与文风。"
        WritingTask.Volume -> "拟一个卷名，只输出卷名一行。当前卷：$instruction"
        WritingTask.Handoff -> """只输出 JSON 对象，字段 plot（本章剧情）、lessons（写作经验）、carry（下章必须承接）、directions（后续走向字符串数组）。"""
        WritingTask.Extract -> """从目标正文提取一条人物、设定或事件。只输出 JSON 对象，包括 title、content、quote（逐字原文证据）及 kind（Character、Setting 或 Event）。"""
        WritingTask.Analyze -> "拆解参考样本的结构、节奏、视角、冲突与钩子。提炼可应用的写作方法。"
        WritingTask.Compress -> "压缩设定、剧情摘要与人物状态。保留具体名称、约束、未回收铺垫和后续承接。"
    }
    val triggerText = listOfNotNull(chapter?.title, chapter?.summary, instruction, required).joinToString("\n")
    val optional = buildList {
        if (book.context.includeMemory) {
            previous?.let { add("上一章交接\n${it.plot}\n${it.lessons}\n${it.carry}\n选定走向：${it.selected}") }
            if (book.context.compressed.isNotBlank()) add("已压缩记忆\n${book.context.compressed}")
        }
        if (book.notes.isNotBlank()) add("作品设定\n${book.notes}")
        if (book.outline.isNotBlank()) add("全书大纲\n${book.outline}")
        if (book.context.includeFeedback) chapter?.feedback.orEmpty().forEach {
            add("作者反馈\n原段：${it.anchor.quote.take(500)}\n${it.prompt}\n${it.annotation}\n评价：${it.vote}")
        }
        book.materials.filter { item ->
            item.enabled && when (item.kind) {
                WritingMaterialKind.Worldbook -> book.context.includeWorldbook &&
                    item.keywords.split(',', '，', '\n').any { it.isNotBlank() && triggerText.contains(it.trim(), ignoreCase = true) }
                WritingMaterialKind.Skill -> book.context.includeSkills
                else -> book.context.includeMaterials
            }
        }.forEach { add("${it.kind.label} · ${it.title}\n${it.content}") }
        if (task == WritingTask.Compress && book.context.includeMemory) {
            book.chapters.takeLast(30).asReversed().forEach { item ->
                val plot = item.handoff?.plot ?: item.summary
                if (plot.isNotBlank()) add("${item.title}\n$plot")
            }
        }
    }
    var remaining = book.context.budget
    val context = optional.mapNotNull { entry ->
        val available = (remaining - 2).coerceAtLeast(0)
        if (available == 0) null else entry.take(available).also { remaining -= it.length + 2 }
    }.joinToString("\n\n")
    return buildString {
        appendLine("作品：${book.title}")
        if (chapter != null) appendLine("章节：${chapter.title}")
        appendLine(directive)
        if (instruction.isNotBlank() && task != WritingTask.Analyze) appendLine("作者要求：$instruction")
        if (context.isNotBlank()) appendLine("\n参考资料\n$context")
        if (required.isNotBlank()) appendLine("\n任务输入\n$required")
    }
}

internal fun parseWritingJson(text: String) =
    writingJson.parseToJsonElement(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

internal fun writingStrings(value: kotlinx.serialization.json.JsonElement?): List<String> =
    (value as? JsonArray ?: error("需要 JSON 字符串数组。")).map {
        val item = it as? JsonPrimitive ?: error("需要文本条目。")
        require(item.isString)
        item.contentOrNull.orEmpty()
    }

internal fun writingHandoff(text: String): WritingHandoff {
    val data = parseWritingJson(text) as? JsonObject ?: error("需要交接 JSON 对象。")
    val plot = data.string("plot")
    val carry = data.string("carry")
    require(plot.isNotBlank() && carry.isNotBlank()) { "请填写本章剧情和下一章承接。" }
    return WritingHandoff(plot, data.string("lessons"), carry, writingStrings(data["directions"]))
}

internal fun writingExtract(candidate: WritingCandidate, chapter: WritingChapter): WritingMaterial {
    val data = parseWritingJson(candidate.text) as? JsonObject ?: error("需要资料 JSON 对象。")
    val quote = data.string("quote")
    val title = data.string("title")
    val kind = WritingMaterialKind.valueOf(data.string("kind"))
    val offset = chapter.text.indexOf(quote)
    require(title.isNotBlank() && quote.isNotBlank() && offset >= 0) { "候选缺少可定位的逐字原文证据。" }
    require(kind in setOf(WritingMaterialKind.Character, WritingMaterialKind.Setting, WritingMaterialKind.Event))
    return WritingMaterial(kind = kind, title = title, content = data.string("content"), anchors = listOf(
        WritingAnchor(chapter.id, offset, quote),
    ))
}
