package com.kreyenhagen.homewall.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kreyenhagen.homewall.domain.Grades

/** "V5", or an outlined "Project" for an ungraded route. */
@Composable
fun GradeChip(grade: Int?, modifier: Modifier = Modifier, large: Boolean = false) {
    val graded = grade != null
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (graded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (graded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (graded) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = Grades.label(grade),
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = if (large) Modifier.padding(horizontal = 14.dp, vertical = 6.dp) else Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
