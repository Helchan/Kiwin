package com.euver.kiwi.domain.model

/**
 * 方法基础信息数据模型
 * 包含包路径、HTTP 请求方法类型、请求路径、方法全限定名、类功能注释和方法功能注释
 */
data class MethodInfo(
    val packageName: String,
    val simpleClassName: String,
    val methodSignature: String,
    val httpMethod: String,
    val requestPath: String,
    val qualifiedName: String,
    val classComment: String,
    val functionComment: String
) {
    /**
     * 判断是否为供外部调用的接口（有 HTTP 方法或请求路径）
     */
    fun isExternalInterface(): Boolean {
        return httpMethod.isNotEmpty() || requestPath.isNotEmpty()
    }

    /**
     * 构建格式化输出内容
     */
    fun toFormattedString(): String {
        val builder = StringBuilder()
        builder.appendLine("请求类型: ${httpMethod.ifEmpty { "(无)" }}")
        builder.appendLine("请求路径: ${requestPath.ifEmpty { "(无)" }}")
        builder.appendLine("方法全限定名: $qualifiedName")
        builder.appendLine("类功能注释: ${classComment.ifEmpty { "(无)" }}")
        builder.appendLine("方法功能注释: ${functionComment.ifEmpty { "(无)" }}")
        return builder.toString()
    }
}
