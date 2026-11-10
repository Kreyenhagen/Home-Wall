package com.kreyenhagen.homewall.domain

/** Route List sort order (§8.1). Persisted by name in settings, so don't rename entries. */
enum class RouteSort { GRADE_HIGH_TO_LOW, GRADE_LOW_TO_HIGH, NEWEST, OLDEST }

/** Route List grade filter (§8.1). The default shows everything. */
data class GradeFilter(
    val minGrade: Int = Grades.MIN,
    val maxGrade: Int = Grades.MAX,
    val includeProjects: Boolean = true,
) {
    val isActive: Boolean
        get() = minGrade != Grades.MIN || maxGrade != Grades.MAX || !includeProjects
}
