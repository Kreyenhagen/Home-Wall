package com.kreyenhagen.homewall.domain

/** Grades are V0..V17; `null` means Project (ungraded) (§6.3). */
object Grades {
    const val MIN = 0
    const val MAX = 17

    fun label(grade: Int?): String = if (grade == null) "Project" else "V$grade"
}
