package com.comfypilot.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 工作流读取路径要和真实前端一致：`workflows/<相对路径>` 整体编码成一个路径段，
 * 斜杠也变成 `%2F`；列表里的中文、空格同样要编码。
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
    fun `workflow path is encoded as one segment with the workflows prefix`() {
        val request = ApiPaths.resolve(
            "http://host:8188",
            ApiCall.FetchUserData("sub dir/我的 工作流.json"),
        )
        assertEquals(
            "http://host:8188/userdata/workflows%2Fsub%20dir%2F%E6%88%91%E7%9A%84%20%E5%B7%A5%E4%BD%9C%E6%B5%81.json",
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
