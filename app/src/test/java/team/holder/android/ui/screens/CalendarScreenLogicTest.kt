package team.holder.android.ui.screens

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.holder.android.HolderCard
import team.holder.android.HolderMilestone

private fun epochSecondsAt(date: LocalDate): Long = date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()

private fun milestone(id: String, startAt: Long) = HolderMilestone(
    milestoneId = id,
    cardId = "card-$id",
    startAt = startAt,
    endAt = null,
    allDay = true,
    kind = null,
    description = null,
    createdAt = startAt,
    updatedAt = startAt,
    cardTitle = null,
)

private fun card(id: String, createdAt: Long, updatedAt: Long = createdAt) = HolderCard(
    cardId = id,
    projectId = "project-1",
    title = id,
    parentCardId = null,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortKey = 0.0,
)

class CalendarScreenLogicTest {
    @Test
    fun calendarDayActivity_bucketsMilestonesByStartDay() {
        val day = LocalDate.of(2026, 8, 22)
        val milestones = listOf(milestone("m1", epochSecondsAt(day)))

        val activity = calendarDayActivity(milestones, emptyList())

        assertEquals(setOf(day), activity.milestoneDays)
        assertTrue(activity.createdDays.isEmpty())
        assertTrue(activity.updatedDays.isEmpty())
    }

    @Test
    fun calendarDayActivity_marksCreatedOnlyWhenNeverUpdated() {
        val day = LocalDate.of(2026, 8, 22)
        val untouched = card("untouched", createdAt = epochSecondsAt(day))

        val activity = calendarDayActivity(emptyList(), listOf(untouched))

        assertEquals(setOf(day), activity.createdDays)
        assertTrue(activity.updatedDays.isEmpty())
    }

    @Test
    fun calendarDayActivity_marksBothCreatedAndUpdatedWhenTheyDifferByDay() {
        val createdDay = LocalDate.of(2026, 8, 20)
        val updatedDay = LocalDate.of(2026, 8, 22)
        val edited = card("edited", createdAt = epochSecondsAt(createdDay), updatedAt = epochSecondsAt(updatedDay))

        val activity = calendarDayActivity(emptyList(), listOf(edited))

        assertEquals(setOf(createdDay), activity.createdDays)
        assertEquals(setOf(updatedDay), activity.updatedDays)
    }

    @Test
    fun monthGridDates_padsToWholeWeeksStartingOnFirstDayOfWeek() {
        // August 2026 starts on a Saturday.
        val dates = monthGridDates(YearMonth.of(2026, 8), DayOfWeek.SUNDAY)

        assertEquals(0, dates.size % 7)
        assertEquals(DayOfWeek.SUNDAY, dates.first().dayOfWeek)
        assertTrue(dates.contains(LocalDate.of(2026, 8, 1)))
        assertTrue(dates.contains(LocalDate.of(2026, 8, 31)))
        // Leading days come from the prior month.
        assertEquals(LocalDate.of(2026, 7, 26), dates.first())
    }

    @Test
    fun monthGridDates_needsNoLeadingDaysWhenMonthStartsOnFirstDayOfWeek() {
        // September 2026 starts on a Tuesday.
        val dates = monthGridDates(YearMonth.of(2026, 9), DayOfWeek.TUESDAY)

        assertEquals(LocalDate.of(2026, 9, 1), dates.first())
        assertEquals(0, dates.size % 7)
    }

    @Test
    fun monthGridDates_respectsALocaleWhereTheWeekStartsOnMonday() {
        // August 2026 starts on a Saturday; a Monday-first week needs 5 leading days.
        val dates = monthGridDates(YearMonth.of(2026, 8), DayOfWeek.MONDAY)

        assertEquals(LocalDate.of(2026, 7, 27), dates.first())
        assertEquals(DayOfWeek.MONDAY, dates.first().dayOfWeek)
    }

    @Test
    fun monthGridDates_coversLeapFebruaryWithoutDroppingOrDuplicatingDays() {
        val dates = monthGridDates(YearMonth.of(2028, 2), DayOfWeek.SUNDAY)

        assertTrue(dates.contains(LocalDate.of(2028, 2, 29)))
        assertEquals(dates.size, dates.distinct().size)
        assertEquals(0, dates.size % 7)
    }
}
