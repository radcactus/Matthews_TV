package app.marquee.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.marquee.tv.data.Repo
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit) {
    var email by remember { mutableStateOf(Repo.prefs.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { emailFocus.requestFocus() } }

    fun submit() {
        if (busy || email.isBlank() || password.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                Repo.login(email, password)
                Repo.prefs.skippedLogin = false
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Sign in failed"
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(460.dp)
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sign in to Stremio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Matthews TV uses your Stremio account for your addons, library and watch history. Your password goes only to Stremio.",
                color = TextDim,
                fontSize = 13.sp,
            )
            TvTextField(
                email, { email = it }, "Email",
                modifier = Modifier.focusRequester(emailFocus),
                imeAction = ImeAction.Next,
                onImeAction = { runCatching { passwordFocus.requestFocus() } },
            )
            TvTextField(
                password, { password = it }, "Password",
                modifier = Modifier.focusRequester(passwordFocus),
                password = true,
                imeAction = ImeAction.Done,
                onImeAction = { submit() },
            )
            error?.let { Text(it, color = Accent, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                ActionButton(if (busy) "Signing in…" else "Sign in", primary = true, onClick = { submit() })
                ActionButton("Skip for now", onClick = {
                    Repo.prefs.skippedLogin = true
                    onDone()
                })
            }
        }
    }
}
