package com.comfypilot.core.workflow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 用本机真实工作流与真实 `/object_info` 做诊断，不作为 CI 依赖。
 *
 * 用法（Windows PowerShell）：
 * ```
 * $env:CP_REAL_WORKFLOWS="<工作流目录>"
 * $env:CP_REAL_OBJECT_INFO="<object_info.json>"
 * .\gradlew.bat :shared:jvmTest --tests "*RealWorkflowConversionTest*"
 * ```
 * 没设置环境变量时直接返回（不做断言），仓库里不提交用户工作流。
 */
class RealWorkflowConversionTest {

    @Test
    fun convertsEveryRealWorkflow() {
        val dirPath = System.getenv("CP_REAL_WORKFLOWS") ?: return
        val infoPath = System.getenv("CP_REAL_OBJECT_INFO") ?: return
        val dir = File(dirPath)
        val infoFile = File(infoPath)
        if (!dir.isDirectory || !infoFile.isFile) return

        val definitions = loadDefinitions(infoFile.readText())
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "没有找到工作流文件")

        val failures = mutableListOf<String>()
        for (file in files) {
            val raw = file.readText()
            val parsed = WorkflowCodec.parseDocument(raw)
            if (parsed !is WorkflowCodec.Parsed.Ok) {
                failures += "${file.name}: 识别失败 ${parsed}"
                continue
            }
            val document = WorkflowDocument(
                path = file.name,
                name = file.name,
                raw = raw,
                format = parsed.format,
                nodeClasses = parsed.nodeClasses,
            )
            when (val result = PromptBuilder.build(document, definitions)) {
                is PromptBuild.Ready -> {
                    val fields = WorkflowFields.build(document, definitions)
                    println(
                        "${file.name}: nodes=${result.prompt.size} " +
                            "commonFields=${fields.commonFields.size} warnings=${result.warnings.size}",
                    )
                    result.warnings.forEach { println("    WARN $it") }
                    System.getenv("CP_REAL_DUMP_DIR")?.let { dumpDir ->
                        val out = File(dumpDir)
                        out.mkdirs()
                        File(out, file.name).writeText(result.prompt.toString())
                    }
                    assertTrue(result.prompt.isNotEmpty())
                }

                is PromptBuild.Blocked -> {
                    failures += "${file.name}: " + result.problems.joinToString("; ") { it.format() }
                }
            }
        }

        assertTrue(failures.isEmpty(), "真实工作流转换失败：\n" + failures.joinToString("\n"))
    }

    private fun loadDefinitions(text: String): Map<String, NodeDefinition> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
            ?: return emptyMap()
        return root.mapNotNull { (className, entry) ->
            val single = buildJsonObject { put(className, entry) }.toString()
            WorkflowCodec.parseNodeDefinition(single, className)?.let { className to it }
        }.toMap()
    }
}
