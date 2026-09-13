package com.jn.dyparse

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

fun copyPlainText(
    context: Context,
    clipboardManager: ClipboardManager,
    value: String,
    toastText: String = "\u5df2\u590d\u5236"
) {
    clipboardManager.setText(AnnotatedString(value))
    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
}
