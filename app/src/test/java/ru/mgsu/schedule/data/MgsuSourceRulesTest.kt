package ru.mgsu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MgsuSourceRulesTest {
    private val currentIpGsUrl =
        "https://mgsu.ru/student/Raspisanie_zanyatii_i_ekzamenov/fayly-raspisaniya-dlya-skachivaniya/IPGSb_1k_0210.pdf"

    @Test
    fun currentLiveIpGsLinkMatchesFirstCourse() {
        assertTrue(
            MgsuSourceRules.matchesStudentSelection(
                currentIpGsUrl,
                "Занятия · ИПГС бак 1 курс",
                "ИПГС",
                1
            )
        )
        assertFalse(MgsuSourceRules.matchesStudentSelection(currentIpGsUrl, "ИПГС бак 1 курс", "ИПГС", 2))
        assertFalse(MgsuSourceRules.matchesStudentSelection(currentIpGsUrl, "ИПГС бак 1 курс", "ИАГ", 1))
    }

    @Test
    fun courseRangeMatchesCourseInsideRange() {
        assertTrue(
            MgsuSourceRules.matchesStudentSelection(
                "https://mgsu.ru/student/schedule/ipgs.pdf",
                "ИПГС бак 1-4 курс",
                "ИПГС",
                3
            )
        )
        assertFalse(
            MgsuSourceRules.matchesStudentSelection(
                "https://mgsu.ru/student/schedule/ipgs.pdf",
                "ИПГС бак 1-4 курс",
                "ИПГС",
                5
            )
        )
    }

    @Test
    fun currentPdfGroupHeaderCanonicalizes() {
        assertEquals("ИПГС 1-1", ScheduleParsingRules.canonicalGroup("ИПГС 1к 1 bo 08.03.01_ПГС"))
        assertEquals("ИПГС 1-12", ScheduleParsingRules.canonicalGroup("ИПГС 1к 12"))
    }

    @Test
    fun officialHostsAreAcceptedButExternalHostsAreRejected() {
        assertTrue(MgsuSourceRules.isOfficialUrl("https://mgsu.ru/a.pdf"))
        assertTrue(MgsuSourceRules.isOfficialUrl("https://www-20.mgsu.ru/a.pdf"))
        assertFalse(MgsuSourceRules.isOfficialUrl("https://example.com/a.pdf"))
    }
}
