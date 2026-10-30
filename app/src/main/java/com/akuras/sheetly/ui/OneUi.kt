package com.akuras.sheetly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akuras.sheetly.ui.theme.LocalIsDarkTheme

/** Share of the screen the large title takes, which moves content into thumb reach. */
private const val HEADER_SCREEN_FRACTION = 0.27f
private val GroupCorner = 26.dp
private val InnerCorner = 4.dp

@Composable
fun largeHeaderHeight(): Dp = (LocalConfiguration.current.screenHeightDp * HEADER_SCREEN_FRACTION).dp

/** The big title at the top of a screen, which scrolls away with the content. */
@Composable
fun LargeHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(largeHeaderHeight())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Takes over the title once the large header has scrolled out of view. */
@Composable
fun CollapsingTopBar(
    title: String,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, label = "titleAlpha")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = titleAlpha))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        navigation()
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .alpha(titleAlpha),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}

@Composable
fun rememberHeaderCollapsed(state: LazyListState): State<Boolean> {
    return remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 240 }
    }
}

/** Corners of a row inside a group of rows: round at the group's ends, tight in between. */
fun groupItemShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) GroupCorner else InnerCorner,
    topEnd = if (index == 0) GroupCorner else InnerCorner,
    bottomStart = if (index == count - 1) GroupCorner else InnerCorner,
    bottomEnd = if (index == count - 1) GroupCorner else InnerCorner,
)

/** A white rounded block on the grey canvas, the basic building block of a settings screen. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(GroupCorner),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            content()
        }
    }
}

val PillShape = RoundedCornerShape(50)

// Measured from One UI's floating tab bar. Switching is instant on purpose, the original shows no
// intermediate frames. The selected segment is a translucent wash rather than a solid fill, so where
// two segments overlap the highlight stays symmetric in both directions.
private val NavTrackDark = Color(0xFF252528)
private val NavSelectedContentDark = Color(0xFFFCFBFE)
private val NavUnselectedContentDark = Color(0xFFE8E7EA)
private val NavTrackLight = Color(0xFFE4E4E7)
private val NavSelectedContentLight = Color(0xFF17171A)
private val NavUnselectedContentLight = Color(0xFF48484B)
private const val NavSelectedWashDark = 0.10f
private const val NavSelectedWashLight = 0.89f
private val NavSegmentHeight = 51.2.dp
private val NavSegmentOverlap = 8.5.dp
private val NavTrackPadding = 4.3.dp

/** The space One UI leaves between the tab bar and the bottom edge. */
val NavBarBottomMargin = 8.5.dp

class NavPillItem(
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector = selectedIcon,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Floating tab bar in the exact proportions of One UI's. */
@Composable
fun OneUiNavPill(items: List<NavPillItem>, segmentWidth: Dp, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    Row(
        modifier
            .shadow(3.dp, PillShape)
            .clip(PillShape)
            .background(if (dark) NavTrackDark else NavTrackLight)
            .padding(NavTrackPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(-NavSegmentOverlap),
    ) {
        items.forEach { item -> NavPillSegment(item, Modifier.size(segmentWidth, NavSegmentHeight)) }
    }
}

@Composable
private fun NavPillSegment(item: NavPillItem, modifier: Modifier) {
    val dark = LocalIsDarkTheme.current
    val haptics = LocalHapticFeedback.current
    val background = when {
        !item.selected -> Color.Transparent
        dark -> Color.White.copy(alpha = NavSelectedWashDark)
        else -> Color.White.copy(alpha = NavSelectedWashLight)
    }
    val content = when {
        item.selected -> if (dark) NavSelectedContentDark else NavSelectedContentLight
        else -> if (dark) NavUnselectedContentDark else NavUnselectedContentLight
    }.copy(alpha = if (item.enabled) 1f else 0.38f)
    Column(
        modifier
            .clip(PillShape)
            .background(background)
            .selectable(
                selected = item.selected,
                enabled = item.enabled,
                role = Role.Tab,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    item.onClick()
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.5.dp, Alignment.CenterVertically),
    ) {
        Icon(
            if (item.selected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
        Text(
            item.label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = content,
            fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** The same pill as a form control, its segments share the available width. */
@Composable
fun <T> OneUiChoicePill(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(if (dark) NavTrackDark else NavTrackLight)
            .padding(NavTrackPadding)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background = when {
                !isSelected -> Color.Transparent
                dark -> Color.White.copy(alpha = NavSelectedWashDark)
                else -> Color.White.copy(alpha = NavSelectedWashLight)
            }
            val content = when {
                isSelected -> if (dark) NavSelectedContentDark else NavSelectedContentLight
                else -> if (dark) NavUnselectedContentDark else NavUnselectedContentLight
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(PillShape)
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelected(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 13.sp,
                    color = content,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

private val SwitchTrackWidth = 52.dp
private val SwitchTrackHeight = 32.dp
private val SwitchThumbSize = 24.dp
private val SwitchThumbPadding = 4.dp

/** A wide, fully rounded track with a large thumb and a flat blue or grey fill. */
@Composable
fun OneUiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(150),
        label = "switchTrack",
    )
    val thumbOffset by animateDpAsState(
        if (checked) SwitchTrackWidth - SwitchThumbSize - SwitchThumbPadding else SwitchThumbPadding,
        animationSpec = tween(150),
        label = "switchThumb",
    )
    Box(
        modifier
            .size(SwitchTrackWidth, SwitchTrackHeight)
            .clip(PillShape)
            .background(trackColor)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(SwitchThumbSize)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** One chrome for every dialog: flat and generously rounded, the way One UI draws its popups. */
@Composable
fun OneUiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        modifier = modifier,
        title = title,
        text = text,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    )
}

/** Bright, solid colours in the style of One UI's tool icons. */
object ToolColors {
    val Blue = Color(0xFF3E91FF)
    val Green = Color(0xFF34C17A)
    val Orange = Color(0xFFFF9A3E)
    val Red = Color(0xFFF2605B)
    val Purple = Color(0xFF9B7BF2)
    val Teal = Color(0xFF22B8C7)
}
