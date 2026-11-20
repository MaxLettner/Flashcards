package com.maxlettner.flashcards

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Leitner box -> days until next review. */
private val INTERVALS = intArrayOf(0, 1, 2, 4, 8, 16, 32, 64)

data class Card(
    val id: String = UUID.randomUUID().toString(),
    val front: String,
    val back: String,
    val box: Int = 0,
    val dueEpochDay: Long = LocalDate.now().toEpochDay(),
) {
    val isDue get() = dueEpochDay <= LocalDate.now().toEpochDay()

    fun reviewed(correct: Boolean): Card {
        val newBox = if (correct) (box + 1).coerceAtMost(INTERVALS.lastIndex) else 0
        // Wrong answers stay due today so they come back in this session.
        val days = if (correct) INTERVALS[newBox].coerceAtLeast(1) else 0
        return copy(box = newBox, dueEpochDay = LocalDate.now().toEpochDay() + days)
    }
}

/** Cards persisted as a JSON file in app storage. */
class CardStore(context: Context) {
    private val file = File(context.filesDir, "cards.json")

    fun load(): List<Card> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Card(o.getString("id"), o.getString("front"), o.getString("back"),
                    o.optInt("box"), o.optLong("due"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(cards: List<Card>) {
        val arr = JSONArray()
        cards.forEach {
            arr.put(JSONObject().put("id", it.id).put("front", it.front).put("back", it.back)
                .put("box", it.box).put("due", it.dueEpochDay))
        }
        val tmp = File(file.parentFile, "cards.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
