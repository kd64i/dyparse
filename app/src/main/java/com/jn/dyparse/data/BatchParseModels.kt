package com.jn.dyparse.data

sealed class BatchParseResult {
    object Idle : BatchParseResult()

    data class Loading(
        val author: String? = null,
        val currentCount: Int = 0,
        val requestedCount: Int? = null,
        val message: String = "",
        val summary: BatchAuthorParseSummary? = null,
        val works: List<ParseResult.Success> = emptyList(),
        val failedCount: Int = 0
    ) : BatchParseResult()

    data class Success(
        val summary: BatchAuthorParseSummary,
        val works: List<ParseResult.Success>
    ) : BatchParseResult()

    data class Error(val msg: String) : BatchParseResult()
}

data class BatchAuthorParseSummary(
    val batchId: String,
    val author: String,
    val authorUid: String? = null,
    val authorSecUid: String? = null,
    val avatarUrl: String? = null,
    val inputUrl: String,
    val resolvedUrl: String? = null,
    val authorUrl: String? = null,
    val requestedCount: Int? = null,
    val parsedCount: Int,
    val totalAvailableCount: Int,
    val isComplete: Boolean,
    val videoCount: Int,
    val imageCount: Int,
    val totalMediaCount: Int,
    val parseTimestamp: Long = System.currentTimeMillis() / 1000
)
