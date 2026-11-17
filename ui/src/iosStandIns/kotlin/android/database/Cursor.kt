package android.database

/** Content queries find nothing on iOS (ContentResolver), so a cursor is never handed out. */
interface Cursor : AutoCloseable {
    val count: Int
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndex(name: String): Int
    fun getColumnIndexOrThrow(name: String): Int
    fun getString(index: Int): String?
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun isNull(index: Int): Boolean
}
