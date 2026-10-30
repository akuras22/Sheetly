package com.akuras.sheetly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// No font is set on purpose: the system font follows the device, on Samsung phones that is One UI's.
private val Base = Typography()

val AppTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 20.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp),
)
