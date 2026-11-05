package app.nbult.lite.data.repo

import app.nbult.lite.data.db.*
import app.nbult.lite.domain.*
import java.io.*
import java.security.MessageDigest

/** Disposable derived data. The database snapshot is always authoritative. */
internal class WeekExpansionCache(private val file: File) {
    private var memoryKey: String? = null
    private var memory: List<List<TimetableEntry>>? = null

    @Synchronized
    fun clear() { memoryKey = null; memory = null; file.delete(); File(file.path + ".tmp").delete() }

    @Synchronized
    fun get(term: Term?, entries: List<TimetableEntry>, overrides: List<SlotOverride>): List<List<TimetableEntry>> {
        val count = (term?.totalWeeks ?: 20).coerceAtLeast(1)
        val source = "v1|$term|$entries|$overrides"
        val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (memoryKey == key) memory?.let { return it }
        val courses = entries.associateBy { it.slot.id }
        val cached = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readUTF() == key)
                require(input.readInt() == count)
                List(count) {
                    val size = input.readInt(); require(size in 0..10000)
                    List(size) {
                        val original = courses.getValue(input.readLong())
                        val slot = original.slot.copy(dayOfWeek=input.readInt(), startSection=input.readInt(), endSection=input.readInt(), room=input.readUTF())
                        original.copy(slot=slot, suspended=input.readBoolean(), ghostLabel=input.readUTF(), badge=input.readUTF(), overrideId=input.readLong(), sourceWeek=input.readInt(), movedIn=input.readBoolean(), toWeek=input.readInt(), toDay=input.readInt(), toStart=input.readInt(), toEnd=input.readInt(), academicWeek=input.readInt())
                    }
                }.also { require(input.read() == -1) }
            }
        }.getOrNull()
        val result = cached ?: (1..count).map { ScheduleService.weekEntries(entries, it, overrides) }
        if (cached == null) runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeUTF(key); output.writeInt(count)
                result.forEach { week ->
                    output.writeInt(week.size)
                    week.forEach { e ->
                        output.writeLong(e.originSlot.id)
                        output.writeInt(e.slot.dayOfWeek); output.writeInt(e.slot.startSection); output.writeInt(e.slot.endSection); output.writeUTF(e.slot.room)
                        output.writeBoolean(e.suspended); output.writeUTF(e.ghostLabel); output.writeUTF(e.badge); output.writeLong(e.overrideId); output.writeInt(e.sourceWeek); output.writeBoolean(e.movedIn)
                        output.writeInt(e.toWeek); output.writeInt(e.toDay); output.writeInt(e.toStart); output.writeInt(e.toEnd); output.writeInt(e.academicWeek)
                    }
                }
            }
            if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
        }
        memoryKey = key; memory = result
        return result
    }
}
