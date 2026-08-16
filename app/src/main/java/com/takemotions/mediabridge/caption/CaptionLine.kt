package com.takemotions.mediabridge.caption

/**
 * One finalized caption line, as produced by the recognizer plus [Chunker].
 *
 * There is deliberately no translation here: the bridge transcribes, the glasses
 * companion translates. The /caption payload still carries a "ja" field (always
 * null) so the wire contract stays byte-compatible with the Caption Bridge spike.
 */
class CaptionLine(
    val id: Int,
    val text: String,
    val tMs: Long,
)
