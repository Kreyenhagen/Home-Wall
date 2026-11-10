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

    fun matches(route: Route): Boolean =
        if (route.grade == null) includeProjects else route.grade in minGrade..maxGrade

    /** The active-filter chip text (§8.1), e.g. "V3–V7 · no projects". Empty when inactive. */
    val summary: String
        get() = buildList {
            if (minGrade != Grades.MIN || maxGrade != Grades.MAX) {
                add(if (minGrade == maxGrade) Grades.label(minGrade) else "${Grades.label(minGrade)}–${Grades.label(maxGrade)}")
            }
            if (!includeProjects) add("no projects")
        }.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/**
 * The one sort-and-filter used by both Route List and Route Display (§8.1), so swipe order always
 * matches list order.
 */
object RouteSorting {

    private val newestFirst: Comparator<Route> = compareByDescending<Route> { it.createdAt }.thenByDescending { it.id }

    fun comparator(sort: RouteSort): Comparator<Route> = when (sort) {
        RouteSort.NEWEST -> newestFirst
        RouteSort.OLDEST -> compareBy<Route> { it.createdAt }.thenBy { it.id }
        // Projects always come after graded routes; ties break newest-first.
        RouteSort.GRADE_HIGH_TO_LOW -> compareBy<Route> { it.grade == null }.thenByDescending { it.grade ?: 0 }.then(newestFirst)
        RouteSort.GRADE_LOW_TO_HIGH -> compareBy<Route> { it.grade == null }.thenBy { it.grade ?: 0 }.then(newestFirst)
    }

    /**
     * Filters and sorts [routes]. Routes in [alwaysInclude] are kept even if the filter hides them,
     * at their sorted position (§8.2: e.g. a Project just saved while projects are filtered out).
     */
    fun apply(routes: List<Route>, sort: RouteSort, filter: GradeFilter, alwaysInclude: Set<Long> = emptySet()): List<Route> =
        routes.filter { filter.matches(it) || it.id in alwaysInclude }.sortedWith(comparator(sort))
}
