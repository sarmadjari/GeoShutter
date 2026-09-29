package com.sasch.cameragps.sharednew.ui.help

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.help_about_credits
import org.jetbrains.compose.resources.stringResource

/** The names linked in [help_about_credits]; they read the same in every language. */
private val CREDIT_LINKS = listOf(
    "GPL-3.0" to ProjectLinks.LICENSE,
    "Alpha GPS" to ProjectLinks.ALPHA_GPS,
    "furble" to ProjectLinks.FURBLE,
)

/**
 * License and credits for the About card: GeoShutter is free software based on Alpha GPS
 * by Saschl, with Fujifilm support based on furble. Shown on both platforms' Help screen.
 */
@Composable
fun AboutCredits(color: Color) {
    val text = stringResource(Res.string.help_about_credits)
    val linkStyle = TextLinkStyles(
        SpanStyle(color = color, textDecoration = TextDecoration.Underline),
    )
    Text(
        text = buildAnnotatedString {
            append(text)
            for ((name, url) in CREDIT_LINKS) {
                val start = text.indexOf(name)
                if (start >= 0) addLink(LinkAnnotation.Url(url, linkStyle), start, start + name.length)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}
