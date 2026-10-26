package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

class TextInputFocus {
    private val focusedInputs = mutableSetOf<Any>()

    val isFocused: Boolean
        get() = focusedInputs.isNotEmpty()

    internal fun update(token: Any, focused: Boolean) {
        if (focused) focusedInputs.add(token) else focusedInputs.remove(token)
    }
}

val LocalTextInputFocus = staticCompositionLocalOf<TextInputFocus?> { null }

@Composable
internal fun Modifier.trackTextInputFocus(): Modifier {
    val focus = LocalTextInputFocus.current ?: return this
    val token = remember { Any() }
    DisposableEffect(focus, token) {
        onDispose { focus.update(token, false) }
    }
    return onFocusChanged { focus.update(token, it.hasFocus) }
}
