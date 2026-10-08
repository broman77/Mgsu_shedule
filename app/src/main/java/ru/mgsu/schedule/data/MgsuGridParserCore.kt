package ru.mgsu.schedule.data

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android-free structural parser for official NIU MGSU timetable tables.
 *
 * Unlike the legacy parser, this core never guesses a lesson from a global time coordinate.
 * It reconstructs the visible table in this order:
 *   1) exact student group column;
 *   2) the dedicated "Пара" column;
 *   3) monotonic pair-number cycles (1..N) for each weekday;
 *   4) the concrete rectangular cell at group x pair.
 *
 * A lesson can therefore exist only when that pair row actually exists in the PDF table.
 */
internal object MgsuGridParserCore {
    data class Glyph(
        val page: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val text: String
    ) {
        val cx: Float get() = x + width / 2f
    }

    data class Page(
        val number: Int,
        val width: Float,
        val height: Float,
        val glyphs: List<Glyph>
    )

    data class Line(
        val y: Float,
        val x1: Float,
        val x2: Float,
        val text: String
    ) {
        val cx: Float get() = (x1 + x2) / 2f
    }

    data class Column(
        val group: String,
        val header: String,
        val center: Float,
        val left: Float,
        val right: Float,
        val headerY: Float
    )

    data class PairAnchor(val pairNo: Int, val y: Float)

    private data class HeaderCandidate(
        val line: Line,
        val group: String,
        val direct: Boolean,
        val parts: Int
    )

    private data class PairGlyph(val pairNo: Int, val y: Float, val x: Float)
    private data class PairColumn(val center: Float, val cycles: List<List<PairAnchor>>)

    data class DayCycle(
        val day: Int,
        val anchors: List<PairAnchor>,
        val top: Float,
        val bottom: Float
    )

    fun parseLessons(
        page: Page,
        profile: UserProfile,
        sourceUrl: String,
        sourceLabel: String
    ): List<ScheduleEvent> {
        val targetGroup = ScheduleParsingRules.canonicalGroup(profile.group) ?: return emptyList()
        val lines = buildLines(page.glyphs)
        val columns = findGroupColumns(page, lines)
        val column = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()
        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f
        val firstGroupLeft = columns.minOfOrNull { it.left } ?: column.left
        val pairColumn = findPairColumn(page, firstGroupLeft, headerBottom) ?: return emptyList()
        val rawCycles = pairColumn.cycles

        val dayMarkers = findWeekdayMarkers(page, pairColumn.center)
        val cycles = assignAndBoundCycles(rawCycles, dayMarkers, headerBottom, page.height)
        if (cycles.isEmpty()) return emptyList()

        val out = mutableListOf<ScheduleEvent>()
        for (cycle in cycles) {
            val anchorsInDay = cycle.anchors.sortedBy { it.y }
            for (i in anchorsInDay.indices) {
                val anchor = anchorsInDay[i]
                val range = ScheduleParsingRules.officialPairTimes[anchor.pairNo] ?: continue
                val top = if (i == 0) cycle.top else (anchorsInDay[i - 1].y + anchor.y) / 2f
                val bottom = if (i == anchorsInDay.lastIndex) cycle.bottom else (anchor.y + anchorsInDay[i + 1].y) / 2f
                if (bottom <= top + 2f) continue

                val cellLines = extractCellLines(page.glyphs, column.left, column.right, top, bottom)
                val event = buildLessonEvent(
                    lines = cellLines,
                    group = targetGroup,
                    weekday = cycle.day,
                    start = range.first,
                    end = range.second,
                    sourceUrl = sourceUrl,
                    sourceLabel = sourceLabel
                )
                if (event != null) out += event
            }
        }
        return out.distinctBy { it.id }
    }

    fun parseExams(
        page: Page,
        profile: UserProfile,
        sourceUrl: String,
        sourceLabel: String
    ): List<ScheduleEvent> {
        val targetGroup = ScheduleParsingRules.canonicalGroup(profile.group) ?: return emptyList()
        val lines = buildLines(page.glyphs)
        val columns = findGroupColumns(page, lines)
        val column = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()
        val firstGroupLeft = columns.minOfOrNull { it.left } ?: column.left
        val semesterYear = runCatching { LocalDate.parse(profile.semesterStart).year }.getOrElse { LocalDate.now().year }

        val dates = lines.mapNotNull { line ->
            if (line.x1 >= firstGroupLeft) return@mapNotNull null
            val raw = dateRegex.find(line.text)?.value ?: return@mapNotNull null
            val date = parseDate(raw, semesterYear) ?: return@mapNotNull null
            Triple(line.y, raw, date)
        }
            .groupBy { (it.first / 3f).roundToInt() }
            .values.map { it.first() }
            .sortedBy { it.first }

        if (dates.isEmpty()) return emptyList()
        val out = mutableListOf<ScheduleEvent>()
        for (i in dates.indices) {
            val current = dates[i]
            val top = if (i == 0) current.first - localDateGap(dates, i) / 2f else (dates[i - 1].first + current.first) / 2f
            val bottom = if (i == dates.lastIndex) current.first + localDateGap(dates, i) / 2f else (current.first + dates[i + 1].first) / 2f
            val cellLines = extractCellLines(page.glyphs, column.left, column.right, top, bottom)
            if (cellLines.isEmpty()) continue
            val rawCell = cellLines.joinToString(" · ").replace(Regex("\\s+"), " ").trim()
            if (!containsRealLessonText(rawCell)) continue
            val time = findOfficialStart(rawCell)
            val event = buildEvent(
                rawCell = rawCell,
                group = targetGroup,
                weekday = null,
                exactDate = current.third.toString(),
                start = time,
                end = time.takeIf { it.isNotBlank() }?.let(::officialEndForStart).orEmpty(),
                sourceUrl = sourceUrl,
                sourceLabel = sourceLabel,
                forceType = "Экзамен/зачёт"
            )
            if (event != null) out += event
        }
        return out.distinctBy { it.id }
    }

    internal fun buildLines(glyphs: List<Glyph>): List<Line> {
        val buckets = glyphs
            .filter { it.text.isNotBlank() }
            .groupBy { (it.y / 2.8f).roundToInt() }

        val out = mutableListOf<Line>()
        for (bucket in buckets.values) {
            val sorted = bucket.sortedBy { it.x }
            if (sorted.isEmpty()) continue
            var current = mutableListOf(sorted.first())

            fun flush() {
                if (current.isEmpty()) return
                val text = joinGlyphs(current)
                if (text.isNotBlank()) {
                    out += Line(
                        y = current.map { it.y }.average().toFloat(),
                        x1 = current.minOf { it.x },
                        x2 = current.maxOf { it.x + it.width },
                        text = text
                    )
                }
                current = mutableListOf()
            }

            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val next = sorted[i]
                val gap = next.x - (prev.x + prev.width)
                if (gap > max(15f, next.height * 1.55f)) {
                    flush()
                    current += next
                } else {
                    current += next
                }
            }
            flush()
        }
        return out.sortedWith(compareBy<Line> { it.y }.thenBy { it.x1 })
    }

    internal fun findGroupColumns(page: Page, lines: List<Line>): List<Column> {
        val headerZone = lines.filter { it.y < page.height * .27f && it.x1 > page.width * .06f }
        val candidates = mutableListOf<HeaderCandidate>()

        // A complete line that already contains one canonical group is always the safest
        // header candidate. Reconstructed windows exist only for PDFs that split the header.
        headerZone.forEach { line ->
            ScheduleParsingRules.canonicalGroup(line.text)?.let { group ->
                candidates += HeaderCandidate(line, group, direct = true, parts = 1)
            }
        }

        headerZone.groupBy { (it.y / 4f).roundToInt() }.values.forEach { sameLine ->
            val ordered = sameLine.sortedBy { it.x1 }
            for (start in ordered.indices) {
                for (length in 2..5) {
                    if (start + length > ordered.size) break
                    val parts = ordered.subList(start, start + length)
                    if (parts.zipWithNext().any { (a, b) -> b.x1 - a.x2 > 85f }) continue
                    val text = parts.joinToString(" ") { it.text }
                    val group = ScheduleParsingRules.canonicalGroup(text) ?: continue
                    candidates += HeaderCandidate(
                        line = Line(
                            y = parts.map { it.y }.average().toFloat(),
                            x1 = parts.minOf { it.x1 },
                            x2 = parts.maxOf { it.x2 },
                            text = text
                        ),
                        group = group,
                        direct = false,
                        parts = length
                    )
                }
            }
        }

        val best = candidates
            .groupBy { it.group }
            .mapNotNull { (_, values) ->
                values.minWithOrNull(
                    compareBy<HeaderCandidate> { it.line.y }
                        .thenBy { if (it.direct) 0 else 1 }
                        .thenBy { it.parts }
                        .thenBy { it.line.x2 - it.line.x1 }
                )
            }
            .sortedBy { it.line.cx }

        if (best.isEmpty()) return emptyList()
        val centers = best.map { it.line.cx }
        return best.mapIndexed { index, item ->
            val line = item.line
            val group = item.group
            val center = centers[index]
            val left = when {
                centers.size == 1 -> (line.x1 - page.width * .08f).coerceAtLeast(page.width * .16f)
                index == 0 -> center - (centers[1] - center) / 2f
                else -> (centers[index - 1] + center) / 2f
            }.coerceAtLeast(0f)
            val right = when {
                centers.size == 1 -> (line.x2 + page.width * .42f).coerceAtMost(page.width)
                index == centers.lastIndex -> center + (center - centers[index - 1]) / 2f
                else -> (center + centers[index + 1]) / 2f
            }.coerceAtMost(page.width)
            Column(group, line.text, center, left, right, line.y)
        }.filter { it.right - it.left > 20f }
    }

    /**
     * Finds the real "Пара" column from the repeated numeric structure itself. Some official
     * PDFs expose the header as one merged string ("Дни Часы Пара"), while others do not
     * expose the word at all. The pair numbers are still a stable vertical cluster.
     */
    private fun findPairColumn(page: Page, firstGroupLeft: Float, headerBottom: Float): PairColumn? {
        val bucketSize = max(2.5f, page.width * .004f)
        val candidates = page.glyphs.asSequence()
            .filter { it.y > headerBottom && it.cx < firstGroupLeft }
            .mapNotNull { glyph ->
                val value = glyph.text.trim()
                if (!singlePairRegex.matches(value)) return@mapNotNull null
                val no = value.toIntOrNull()?.takeIf { it in 1..8 } ?: return@mapNotNull null
                PairGlyph(no, glyph.y, glyph.cx)
            }
            .groupBy { (it.x / bucketSize).roundToInt() }

        data class Scored(val center: Float, val cycles: List<List<PairAnchor>>, val score: Int)

        return candidates.values.mapNotNull { cluster ->
            val ordered = cluster.sortedBy { it.y }
            val anchors = mutableListOf<PairAnchor>()
            for (item in ordered) {
                val duplicate = anchors.lastOrNull()?.let {
                    it.pairNo == item.pairNo && abs(it.y - item.y) < 3.5f
                } == true
                if (!duplicate) anchors += PairAnchor(item.pairNo, item.y)
            }
            val cycles = splitPairCycles(anchors)
            if (cycles.isEmpty()) return@mapNotNull null
            val strongCycles = cycles.count { cycle ->
                cycle.size >= 5 && cycle.zipWithNext().all { (a, b) -> b.pairNo == a.pairNo + 1 }
            }
            val distinct = anchors.map { it.pairNo }.distinct().size
            val score = strongCycles * 10000 + cycles.size * 1000 + distinct * 100 + anchors.size
            Scored(cluster.map { it.x }.average().toFloat(), cycles, score)
        }.maxByOrNull { it.score }?.let { PairColumn(it.center, it.cycles) }
    }

    internal fun splitPairCycles(anchors: List<PairAnchor>): List<List<PairAnchor>> {
        if (anchors.isEmpty()) return emptyList()
        val cycles = mutableListOf<MutableList<PairAnchor>>()
        for (anchor in anchors.sortedBy { it.y }) {
            val current = cycles.lastOrNull()
            if (current == null || current.isEmpty() || anchor.pairNo <= current.last().pairNo) {
                cycles += mutableListOf(anchor)
            } else {
                current += anchor
            }
        }
        return cycles
            .map { it.toList() }
            .filter { cycle ->
                // The dedicated pair column is structural: rows must advance 1→2→3... .
                // Random digits from time/specialty text cannot form a valid day cycle.
                cycle.size >= 3 && cycle.zipWithNext().all { (a, b) -> b.pairNo == a.pairNo + 1 }
            }
    }

    internal fun assignAndBoundCycles(
        cycles: List<List<PairAnchor>>,
        dayMarkers: Map<Int, Float>,
        headerBottom: Float,
        pageHeight: Float
    ): List<DayCycle> {
        if (cycles.isEmpty()) return emptyList()
        val ordered = cycles.sortedBy { it.first().y }
        val explicitDays = if (ordered.size == 6) {
            (1..6).toList()
        } else {
            ordered.mapIndexed { index, cycle ->
                val center = (cycle.first().y + cycle.last().y) / 2f
                dayMarkers.minByOrNull { abs(it.value - center) }?.key ?: (index + 1).coerceAtMost(6)
            }
        }

        return ordered.mapIndexed { index, cycle ->
            val first = cycle.first().y
            val last = cycle.last().y
            val ownFirstGap = pairGap(cycle, 0)
            val ownLastGap = pairGap(cycle, cycle.lastIndex)
            val top = if (index == 0) {
                max(headerBottom, first - ownFirstGap / 2f)
            } else {
                (ordered[index - 1].last().y + first) / 2f
            }
            val bottom = if (index == ordered.lastIndex) {
                (last + ownLastGap / 2f).coerceAtMost(pageHeight)
            } else {
                (last + ordered[index + 1].first().y) / 2f
            }
            DayCycle(explicitDays[index], cycle, top, bottom)
        }.filter { it.bottom > it.top + 10f }
    }

    internal fun extractCellLines(
        glyphs: List<Glyph>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ): List<String> {
        val selected = glyphs.filter { g ->
            g.cx > left + 1f && g.cx < right - 1f && g.y > top + 1f && g.y < bottom - 1f
        }
        if (selected.isEmpty()) return emptyList()
        return selected.groupBy { (it.y / 2.8f).roundToInt() }
            .values
            .sortedBy { bucket -> bucket.map { it.y }.average() }
            .map { bucket -> joinGlyphs(bucket.sortedBy { it.x }) }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
    }

    private fun buildLessonEvent(
        lines: List<String>,
        group: String,
        weekday: Int,
        start: String,
        end: String,
        sourceUrl: String,
        sourceLabel: String
    ): ScheduleEvent? {
        if (lines.isEmpty()) return null
        val raw = lines.joinToString(" · ").replace(Regex("\\s+"), " ").trim()
        if (!containsRealLessonText(raw)) return null
        return buildEvent(
            rawCell = raw,
            group = group,
            weekday = weekday,
            exactDate = null,
            start = start,
            end = end,
            sourceUrl = sourceUrl,
            sourceLabel = sourceLabel,
            forceType = null
        )
    }

    private fun buildEvent(
        rawCell: String,
        group: String,
        weekday: Int?,
        exactDate: String?,
        start: String,
        end: String,
        sourceUrl: String,
        sourceLabel: String,
        forceType: String?
    ): ScheduleEvent? {
        var cleaned = rawCell.replace(Regex("\\s+"), " ").trim(' ', '-', '|', '·')
        if (!containsRealLessonText(cleaned)) return null

        val teachers = ScheduleParsingRules.extractTeachers(cleaned)
        val teacher = teachers.firstOrNull().orEmpty()
        val rooms = roomRegex.findAll(cleaned)
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .toList()
        val room = rooms.joinToString(" / ").take(120)
        val weeks = ScheduleParsingRules.extractWeekNumbers(cleaned)
        val parity = ScheduleParsingRules.extractParity(cleaned)
        val type = forceType ?: inferType(cleaned)

        cleaned = ScheduleParsingRules.removeWeekSpecs(cleaned)
        cleaned = ScheduleParsingRules.removeTeachers(cleaned)
        rooms.forEach { value -> cleaned = cleaned.replace(value, " ", ignoreCase = true) }
        cleaned = cleaned
            .replace(specialtyCodeRegex, " ")
            .replace(Regex("(?i)\\b(?:неч[её]т(?:ная|ные|н)?|ч[её]т(?:ная|ные|н)?|числитель|знаменатель)\\b"), " ")
            .replace(lessonPrefixRegex, " ")
            .replace(Regex("\\s*·\\s*"), " · ")
            .replace(Regex("(?:\\s*·\\s*){2,}"), " · ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ';', '-', '|', '/', '·')

        if (cleaned.length < 3) return null
        val normalized = norm(cleaned)
        if (normalized in metadataWords || normalized.startsWith("РАСПИСАНИЕ")) return null
        if (cleaned.matches(roomOnlyRegex)) return null
        if (cleaned.count { it.isLetter() } < 3) return null

        val signature = listOf(
            cleaned, group, weekday?.toString().orEmpty(), exactDate.orEmpty(), start, end,
            teachers.joinToString(","), room, weeks.joinToString(","), parity
        ).joinToString("|")

        return ScheduleEvent(
            id = sha1(signature),
            title = cleaned.take(220),
            type = type,
            teacher = teacher,
            group = group,
            room = room,
            weekday = weekday,
            exactDate = exactDate,
            startTime = start,
            endTime = end,
            weekNumbers = weeks,
            weekParity = parity,
            sourceUrl = sourceUrl,
            sourceLabel = sourceLabel
        )
    }

    private fun containsRealLessonText(text: String): Boolean {
        val visible = text.replace('·', ' ').replace(Regex("\\s+"), " ").trim()
        if (visible.length < 3 || visible.count { it.isLetter() } < 3) return false
        val n = norm(visible)
        if (n in metadataWords || n.startsWith("РАСПИСАНИЕ")) return false
        if (specialtyCodeRegex.matches(visible)) return false
        if (roomOnlyRegex.matches(visible)) return false
        val stripped = ScheduleParsingRules.removeTeachers(ScheduleParsingRules.removeWeekSpecs(visible))
            .replace(roomRegex, " ")
            .replace(specialtyCodeRegex, " ")
            .replace(lessonPrefixRegex, " ")
            .replace(Regex("[^А-ЯЁа-яёA-Za-z]"), "")
        return stripped.length >= 3
    }

    private fun inferType(text: String): String {
        val lecture = lectureRegex.containsMatchIn(text)
        val practice = practiceRegex.containsMatchIn(text)
        val lab = labRegex.containsMatchIn(text)
        val kinds = listOf(lecture, practice, lab).count { it }
        return when {
            text.contains("экзам", ignoreCase = true) -> "Экзамен"
            text.contains("зачет", ignoreCase = true) || text.contains("зачёт", ignoreCase = true) -> "Зачёт"
            kinds > 1 -> "Занятие"
            lab -> "Лабораторная"
            practice -> "Практика"
            lecture -> "Лекция"
            else -> "Занятие"
        }
    }

    private fun findHeader(lines: List<Line>, word: String, maxY: Float): Line? {
        val target = norm(word).replace(" ", "")
        return lines
            .filter { it.y <= maxY }
            .firstOrNull { norm(it.text).replace(" ", "") == target }
    }

    private fun inferPairCenter(columns: List<Column>, pageWidth: Float): Float {
        val first = columns.minByOrNull { it.center } ?: return pageWidth * .12f
        val typicalWidth = if (columns.size >= 2) {
            val sorted = columns.sortedBy { it.center }
            (sorted[1].center - sorted[0].center).coerceAtLeast(pageWidth * .12f)
        } else pageWidth * .20f
        return (first.left - typicalWidth * .14f).coerceAtLeast(pageWidth * .05f)
    }

    private fun findWeekdayMarkers(page: Page, pairCenter: Float): Map<Int, Float> {
        val leftGlyphs = page.glyphs.filter { it.cx < pairCenter && it.text.any(Char::isLetter) }
        val found = mutableMapOf<Int, MutableList<Float>>()
        for (bucket in leftGlyphs.groupBy { (it.x / 5f).roundToInt() }.values) {
            val chars = bucket.sortedBy { it.y }.flatMap { glyph ->
                norm(glyph.text).filter(Char::isLetter).map { ch -> ch to glyph.y }
            }
            if (chars.size < 4) continue
            val text = chars.joinToString("") { it.first.toString() }
            for ((name, day) in weekdays) {
                var from = 0
                while (from < text.length) {
                    val at = text.indexOf(name, from)
                    if (at < 0 || at + name.length > chars.size) break
                    val ys = chars.subList(at, at + name.length).map { it.second }
                    found.getOrPut(day) { mutableListOf() } += ys.average().toFloat()
                    from = at + name.length
                }
            }
        }
        return found.mapValues { (_, values) -> values.average().toFloat() }
    }

    private fun pairGap(cycle: List<PairAnchor>, index: Int): Float {
        val gaps = buildList {
            if (index > 0) (cycle[index].y - cycle[index - 1].y).takeIf { it in 8f..200f }?.let(::add)
            if (index < cycle.lastIndex) (cycle[index + 1].y - cycle[index].y).takeIf { it in 8f..200f }?.let(::add)
        }
        return gaps.average().takeIf { !it.isNaN() }?.toFloat() ?: 32f
    }

    private fun localDateGap(dates: List<Triple<Float, String, LocalDate>>, index: Int): Float {
        val gaps = buildList {
            if (index > 0) (dates[index].first - dates[index - 1].first).takeIf { it in 10f..250f }?.let(::add)
            if (index < dates.lastIndex) (dates[index + 1].first - dates[index].first).takeIf { it in 10f..250f }?.let(::add)
        }
        return gaps.average().takeIf { !it.isNaN() }?.toFloat() ?: 48f
    }

    private fun parseDate(raw: String, semesterYear: Int): LocalDate? {
        val clean = raw.trim().trimEnd('.')
        val patterns = listOf("d.M.yyyy", "dd.MM.yyyy", "d.M.yy", "dd.MM.yy")
        for (pattern in patterns) {
            val date = runCatching { LocalDate.parse(clean, DateTimeFormatter.ofPattern(pattern)) }.getOrNull() ?: continue
            if (date.year !in (semesterYear - 1)..(semesterYear + 1)) continue
            return date
        }
        return null
    }

    private fun findOfficialStart(text: String): String {
        colonTimeRegex.findAll(text).forEach { match ->
            val value = normalizeClock(match.value)
            if (ScheduleParsingRules.officialPairTimes.values.any { it.first == value }) return value
        }
        dotTimeRegex.findAll(text).forEach { match ->
            val value = normalizeClock(match.value)
            if (ScheduleParsingRules.officialPairTimes.values.any { it.first == value }) return value
        }
        return ""
    }

    private fun officialEndForStart(start: String): String = ScheduleParsingRules.officialPairTimes.values
        .firstOrNull { it.first == start }?.second.orEmpty()

    private fun joinGlyphs(glyphs: List<Glyph>): String {
        val sb = StringBuilder()
        var previous: Glyph? = null
        for (glyph in glyphs) {
            val prev = previous
            if (prev != null && glyph.x - (prev.x + prev.width) > max(2.8f, glyph.height * .30f)) sb.append(' ')
            sb.append(glyph.text)
            previous = glyph
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeClock(value: String): String {
        val parts = value.replace('.', ':').split(':')
        if (parts.size != 2) return ""
        val h = parts[0].toIntOrNull() ?: return ""
        val m = parts[1].toIntOrNull() ?: return ""
        return "%02d:%02d".format(h, m)
    }

    private fun norm(text: String): String = text.uppercase().replace('Ё', 'Е')
        .replace(Regex("[^А-ЯA-Z0-9. ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val singlePairRegex = Regex("^[1-8]$")
    private val dateRegex = Regex("(?<!\\d)\\d{1,2}[.]\\d{1,2}[.](?:20)?\\d{2}(?!\\d)")
    private val colonTimeRegex = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)")
    private val dotTimeRegex = Regex("(?<!\\d)(?:[01]?\\d|2[0-3])[.][0-5]\\d(?![.]?\\d)")
    private val roomRegex = Regex("(?i)\\b\\d{2,4}(?:[.]\\d+)?\\s*[А-ЯA-Z]?\\s*(?:/\\s*)?(?:КМК|УЛК|УЛБ|КПА|ЛАБ)\\b")
    private val roomOnlyRegex = Regex("(?i)^\\s*\\d{2,4}(?:[.]\\d+)?\\s*[А-ЯA-Z]?\\s*(?:/\\s*)?(?:КМК|УЛК|УЛБ|КПА|ЛАБ)\\s*$")
    private val specialtyCodeRegex = Regex("\\b\\d{2}[.]\\d{2}[.]\\d{2}(?:[_A-Za-zА-Яа-я0-9-]+)?\\b")
    private val lectureRegex = Regex("(?i)(?:^|\\s)(?:л\\.|лекц(?:ия)?)\\s*")
    private val practiceRegex = Regex("(?i)(?:^|\\s)(?:пр\\.|практ(?:ика)?)\\s*")
    private val labRegex = Regex("(?i)(?:^|\\s)(?:лаб\\.|лабор(?:аторная)?)\\s*")
    private val lessonPrefixRegex = Regex("(?i)(?:^|(?<=\\s)|(?<=·))(?:КРП|КОП|л\\.|пр\\.|лаб\\.|лекц(?:ия)?|практ(?:ика)?|лабор(?:аторная)?)\\s*")
    private val metadataWords = setOf("ДНИ", "ЧАСЫ", "ПАРА", "ГРУППА")
    private val weekdays = mapOf(
        "ПОНЕДЕЛЬНИК" to 1,
        "ВТОРНИК" to 2,
        "СРЕДА" to 3,
        "ЧЕТВЕРГ" to 4,
        "ПЯТНИЦА" to 5,
        "СУББОТА" to 6
    )
}
