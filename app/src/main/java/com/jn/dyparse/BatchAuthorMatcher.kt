package com.jn.dyparse

import com.jn.dyparse.data.ParseResult

internal object BatchAuthorMatcher {
    fun matchesItemAuthor(
        item: Map<String, Any>,
        expectedUid: String? = null,
        expectedSecUid: String? = null
    ): Boolean {
        val author = item["author"] as? Map<*, *>
        return matchesAuthor(
            actualUid = author?.get("uid")?.stringValue(),
            actualSecUid = author?.get("sec_uid")?.stringValue(),
            expectedUid = expectedUid,
            expectedSecUid = expectedSecUid
        )
    }

    fun matchesResultAuthor(
        result: ParseResult.Success,
        expectedUid: String? = null,
        expectedSecUid: String? = null
    ): Boolean {
        return matchesAuthor(
            actualUid = result.authorUid,
            actualSecUid = result.authorSecUid,
            expectedUid = expectedUid,
            expectedSecUid = expectedSecUid
        )
    }

    private fun matchesAuthor(
        actualUid: String?,
        actualSecUid: String?,
        expectedUid: String?,
        expectedSecUid: String?
    ): Boolean {
        val normalizedActualUid = actualUid.normalizedId()
        val normalizedActualSecUid = actualSecUid.normalizedId()
        val normalizedExpectedUid = expectedUid.normalizedId()
        val normalizedExpectedSecUid = expectedSecUid.normalizedId()

        val canCompareUid = normalizedActualUid != null && normalizedExpectedUid != null
        val canCompareSecUid = normalizedActualSecUid != null && normalizedExpectedSecUid != null
        if (!canCompareUid && !canCompareSecUid) {
            return true
        }

        return (canCompareUid && normalizedActualUid == normalizedExpectedUid) ||
            (canCompareSecUid && normalizedActualSecUid == normalizedExpectedSecUid)
    }

    private fun Any.stringValue(): String? {
        return toString().normalizedId()
    }

    private fun String?.normalizedId(): String? {
        return this
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
}
