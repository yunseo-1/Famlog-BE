// 초안: android/core/designsystem/src/main/kotlin/com/famlog/core/designsystem/theme/ 로 옮겨서 사용
// 기준: docs/design/design-tokens.json (Figma 변수, 2026-11-12)
// 폰트: res/font/ 에 noto_sans_kr_regular.ttf / _medium.ttf / _bold.ttf 추가 필요 (Google Fonts, OFL 라이선스)
package com.famlog.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famlog.core.designsystem.R

@Immutable
data class FamColors(
    val bgDefault: Color = Color(0xFFFFFFFF),
    val bgSubtle: Color = Color(0xFFF7F7F8),
    val surfaceMuted: Color = Color(0xFFEFEFF1),
    val surfacePlaceholder: Color = Color(0xFFE1E1E5),
    val borderDefault: Color = Color(0xFFE1E1E5),
    val borderStrong: Color = Color(0xFFC8C8CE),
    val textPrimary: Color = Color(0xFF1C1C21),
    val textSecondary: Color = Color(0xFF4A4A52),
    val textTertiary: Color = Color(0xFF8A8A93),
    val textOnBrand: Color = Color(0xFFFFFFFF),
    val brandPrimary: Color = Color(0xFFC94A26),
    val brandAccent: Color = Color(0xFFE8643C),
    val brandSubtle: Color = Color(0xFFFFF1EB),
    val bubbleMine: Color = Color(0xFFFFDCCB),
    val bubbleOther: Color = Color(0xFFEFEFF1),
    val categorySchedule: Color = Color(0xFF2F6FED),
    val categoryScheduleBg: Color = Color(0xFFEAF1FF),
    val categoryAnniversary: Color = Color(0xFFC2255C),
    val categoryAnniversaryBg: Color = Color(0xFFFCEBF3),
    val categoryPlace: Color = Color(0xFF1F9D6B),
    val categoryPlaceBg: Color = Color(0xFFE6F6EF),
    val categoryTodo: Color = Color(0xFF7A4FE0),
    val categoryTodoBg: Color = Color(0xFFF1ECFF),
    val statusDanger: Color = Color(0xFFD93A3A),
)

/** 후보 카드·칩·캘린더·지도 핀에 공통으로 쓰는 유형 색 (패턴 B) */
enum class CandidateType { SCHEDULE, ANNIVERSARY, TODO, PLACE }

fun FamColors.categoryOf(type: CandidateType): Pair<Color, Color> = when (type) {
    CandidateType.SCHEDULE -> categorySchedule to categoryScheduleBg
    CandidateType.ANNIVERSARY -> categoryAnniversary to categoryAnniversaryBg
    CandidateType.TODO -> categoryTodo to categoryTodoBg
    CandidateType.PLACE -> categoryPlace to categoryPlaceBg
}

object FamDimens {
    val xs: Dp = 4.dp; val sm: Dp = 8.dp; val md: Dp = 12.dp; val lg: Dp = 16.dp
    val xl: Dp = 20.dp; val xxl: Dp = 24.dp; val xxxl: Dp = 32.dp
    val radiusSm: Dp = 8.dp; val radiusMd: Dp = 12.dp; val radiusLg: Dp = 16.dp
    val screenPadding: Dp = 16.dp; val appBarHeight: Dp = 56.dp; val bottomNavHeight: Dp = 64.dp
    val minTouchTarget: Dp = 48.dp; val fabSize: Dp = 56.dp
    val candidateCardWidth: Dp = 280.dp; val bubbleMaxTextWidth: Dp = 200.dp; val chatIndent: Dp = 40.dp
}

val NotoSansKr = FontFamily(
    Font(R.font.noto_sans_kr_regular, FontWeight.Normal),
    Font(R.font.noto_sans_kr_medium, FontWeight.Medium),
    Font(R.font.noto_sans_kr_bold, FontWeight.Bold),
)

@Immutable
data class FamType(
    val titleLarge: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    val titleMedium: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp),
    val bodyLarge: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    val bodyMedium: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val bodyMediumStrong: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    val label: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    val caption: TextStyle = TextStyle(fontFamily = NotoSansKr, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

val LocalFamColors = staticCompositionLocalOf { FamColors() }
val LocalFamType = staticCompositionLocalOf { FamType() }

object FamTheme {
    val colors: FamColors @Composable get() = LocalFamColors.current
    val type: FamType @Composable get() = LocalFamType.current
}

@Composable
fun FamLogTheme(content: @Composable () -> Unit) {
    // 다크 모드는 아직 디자인 없음 (Figma Color 컬렉션에 Dark 모드 추가 예정)
    CompositionLocalProvider(
        LocalFamColors provides FamColors(),
        LocalFamType provides FamType(),
        content = content,
    )
}
