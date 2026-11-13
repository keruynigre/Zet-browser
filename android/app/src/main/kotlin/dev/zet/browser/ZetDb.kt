package dev.zet.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Geçmiş ve yer işaretleri (yalnızca normal sekmeler; gizli sekmeler yazılmaz). */
class ZetDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "zet.db", null, 1) {

    data class Entry(val id: Long, val url: String, val title: String, val time: Long, val visits: Int = 0)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', time INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX history_time ON history(time)")
        db.execSQL("CREATE INDEX history_url ON history(url)")
        db.execSQL(
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL DEFAULT '', time INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- Geçmiş -----------------------------------------------------------

    /** Ziyaret ekler; aynı adres son 30 sn içinde eklendiyse yalnızca başlığı günceller. */
    fun addVisit(url: String, title: String) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.rawQuery(
            "SELECT id FROM history WHERE url = ? AND time > ? ORDER BY time DESC LIMIT 1",
            arrayOf(url, (now - 30_000).toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                if (title.isNotBlank()) {
                    db.update("history", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(c.getLong(0).toString()))
                }
                return
            }
        }
        db.insert("history", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("time", now)
        })
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE history SET title = ? WHERE id = (SELECT id FROM history WHERE url = ? ORDER BY time DESC LIMIT 1)",
            arrayOf(title, url),
        )
    }

    fun history(query: String = "", limit: Int = 500): List<Entry> {
        val q = "%$query%"
        return readableDatabase.rawQuery(
            "SELECT id, url, title, time FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY time DESC LIMIT $limit",
            arrayOf(q, q),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    /** En çok ziyaret edilen siteler (alan adına göre değil, adrese göre). */
    fun topSites(limit: Int = 8): List<Entry> = readableDatabase.rawQuery(
        "SELECT MAX(id), url, title, MAX(time), COUNT(*) AS n FROM history " +
            "GROUP BY url ORDER BY n DESC, MAX(time) DESC LIMIT $limit",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
        }
    }

    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    // ---- Yer işaretleri -----------------------------------------------------

    fun isBookmarked(url: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url),
    ).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url)
            put("title", title)
            put("time", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    fun bookmarks(query: String = ""): List<Entry> {
        val q = "%$query%"
        return readableDatabase.rawQuery(
            "SELECT id, url, title, time FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY time DESC",
            arrayOf(q, q),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    companion object {
        @Volatile
        private var instance: ZetDb? = null

        fun get(context: Context): ZetDb =
            instance ?: synchronized(this) {
                instance ?: ZetDb(context.applicationContext).also { instance = it }
            }
    }
}
