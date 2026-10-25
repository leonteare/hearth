// The PIN dialog in front of Settings on a kiosk phone, and the choice it unlocks: Hearth's settings or the
// phone's own. The first time, it asks a grown-up to choose the PIN.
package im.flume.hearth.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import im.flume.hearth.core.ui.theme.HearthTheme

@Composable
internal fun KioskGate(open: Boolean, onDismiss: () -> Unit, onHearthSettings: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val hasPin = remember { KioskAccess.pin(context) != null }
    var entered by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        when {
            hasPin -> if (KioskAccess.matches(context, entered)) unlocked = true else { error = "That's not it"; entered = "" }
            !KioskPin.valid(entered) -> error = "Use 4 to 8 digits"
            entered != again -> { error = "The two PINs don't match"; again = "" }
            else -> { KioskAccess.setPin(context, entered); unlocked = true }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when { unlocked -> "Grown-ups only"; hasPin -> "Enter the PIN"; else -> "Choose a PIN" }) },
        text = {
            Column {
                if (unlocked) {
                    Text(
                        "Hearth settings change this app. Phone settings are for Wi-Fi, the charging limit and updates.",
                        color = HearthTheme.colors.textSecondary,
                    )
                } else {
                    if (!hasPin) {
                        Text("This PIN will guard Settings on this phone. Only grown-ups should know it.", color = HearthTheme.colors.textSecondary)
                    }
                    Spacer(Modifier.height(HearthTheme.spacing.s))
                    PinField(entered, { entered = it; error = null }, if (hasPin) "PIN" else "New PIN")
                    if (!hasPin) {
                        Spacer(Modifier.height(HearthTheme.spacing.s))
                        PinField(again, { again = it; error = null }, "Again")
                    }
                    error?.let {
                        Spacer(Modifier.height(HearthTheme.spacing.s))
                        Text(it, color = HearthTheme.colors.destructive)
                    }
                }
            }
        },
        confirmButton = {
            if (unlocked) {
                TextButton(onClick = { context.activity()?.let(KioskAccess::leave); onDismiss() }) { Text("Phone settings") }
            } else {
                TextButton(onClick = { confirm() }) { Text("OK") }
            }
        },
        dismissButton = {
            if (unlocked) {
                TextButton(onClick = onHearthSettings) { Text("Hearth settings") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The Activity behind a composable's context (there may be themed wrappers in between). */
private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
