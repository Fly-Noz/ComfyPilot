package com.comfypilot.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 工作流路径要按段编码：中文、空格是真实存在的（网页端允许中文文件名），
 * 但 `/` 是目录分隔符，必须保留。
 */
class ApiPathsTest {

    @Test
    fun `workflow list points at the workflows directory`() {
        val request = ApiPaths.resolve("http://host:8188/", ApiCall.ListUserData())
        assertEquals("GET", request.method)
        assertEquals(
            "http://host:8188/userdata?dir=workflows&recurse=true&full_info=true",
            request.url,
        )
    }

    @Test
    fun `workflow path keeps separators and encodes every segment`() {
        val request = ApiPaths.resolve(
            "http://host:8188",
            ApiCall.FetchUserData("sub dir/我的 工作流.json"),
        )
        assertEquals(
            "http://host:8188/userdata/sub%20dir/%E6%88%91%E7%9A%84%20%E5%B7%A5%E4%BD%9C%E6%B5%81.json",
            request.url,
        )
    }

    @Test
    fun `node class is encoded as a single segment`() {
        val request = ApiPaths.resolve(
            "http://host:8188",
            ApiCall.FetchObjectInfo("Some Custom Node"),
        )
        assertEquals("http://host:8188/object_info/Some%20Custom%20Node", request.url)
    }
}
