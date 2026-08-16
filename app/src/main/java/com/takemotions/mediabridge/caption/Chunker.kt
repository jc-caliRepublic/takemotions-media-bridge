package com.takemotions.mediabridge.caption

/**
 * Cuts continuous speech into caption-sized lines.
 *
 * ML Kit only finalizes at end-of-utterance, and podcasts/videos speak for a minute
 * without a pause — so raw finals arrive as whole paragraphs (v0.2.2 field report).
 * This is the caption-lab web chunker (field-proven in v0.1.3) ported into the bridge:
 * watch the growing partial, and every time CHUNK_WORDS uncommitted words pile up,
 * commit all but the still-being-rewritten tail as a line. When the engine's own
 * final lands, flush the remainder using the final's (more accurate) text.
 */
class Chunker {

    companion object {
        /** Cut once this many uncommitted words have accumulated. */
        private const val CHUNK_WORDS = 10

        /** The trailing words the recognizer still rewrites — never commit them early. */
        private const val HOLDBACK_WORDS = 2

        private const val LINE_WORDS = CHUNK_WORDS - HOLDBACK_WORDS
    }

    /** Words of the current utterance already emitted as lines. */
    private var committed = 0

    class Cut(val lines: List<String>, val tail: String)

    /** Feed every partial; returns lines to commit plus the short partial left to show. */
    @Synchronized
    fun onPartial(text: String): Cut {
        val words = split(text)
        if (words.size < committed) {
            // Small shrink = the recognizer merged/rewrote a word or two: clamp.
            // Big shrink = a new utterance or session began without a final: start
            // over (the old utterance's uncommitted tail is lost — rare, accepted).
            committed = if (committed - words.size > 3) 0 else words.size
        }
        val lines = ArrayList<String>(1)
        while (words.size - committed >= CHUNK_WORDS) {
            val cutEnd = committed + LINE_WORDS
            lines.add(join(words, committed, cutEnd))
            committed = cutEnd
        }
        return Cut(lines, join(words, committed, words.size))
    }

    /** Feed engine finals; returns the not-yet-committed remainder as line(s). */
    @Synchronized
    fun onFinal(text: String): List<String> {
        val words = split(text)
        val start = committed
        committed = 0
        if (words.size <= start) return emptyList()
        // A final that never streamed partials first can still be a paragraph; chop it
        // to the same cadence, leaving the tail as one piece of up to CHUNK_WORDS words.
        val lines = ArrayList<String>(1)
        var i = start
        while (words.size - i > CHUNK_WORDS) {
            lines.add(join(words, i, i + LINE_WORDS))
            i += LINE_WORDS
        }
        lines.add(join(words, i, words.size))
        return lines
    }

    private val whitespace = Regex("\\s+")

    private fun split(text: String): List<String> {
        val t = text.trim()
        return if (t.isEmpty()) emptyList() else t.split(whitespace)
    }

    private fun join(words: List<String>, from: Int, to: Int): String =
        words.subList(from, to).joinToString(" ")
}
