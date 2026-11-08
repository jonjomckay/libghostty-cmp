package io.github.xchacha20_poly1305.libghostty.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xchacha20_poly1305.libghostty.compose.*
import io.github.xchacha20_poly1305.libghostty.demo.shared.DemoTerminal
import io.github.xchacha20_poly1305.libghostty.extras.GhosttyThemeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoApp { dark ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
}

private enum class Appearance(val label: Int) {
    System(R.string.theme_system), Light(R.string.theme_light), Dark(R.string.theme_dark),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoApp(onThemeChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("demo", 0) }
    var appearance by remember {
        mutableStateOf(Appearance.entries.firstOrNull { it.name == preferences.getString("appearance", null) }
            ?: Appearance.System)
    }
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    SideEffect { onThemeChanged(dark) }
    val terminal = remember { DemoTerminal() }
    DisposableEffect(terminal) { onDispose { terminal.close() } }
    val state = rememberGhosttyTerminalState()
    val theme = remember(dark) {
        GhosttyThemeStore.loadTheme(
            if (dark) GhosttyThemeStore.DEFAULT_DARK_THEME else GhosttyThemeStore.DEFAULT_LIGHT_THEME,
        )
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(stringResource(appearance.label))
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                Appearance.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(option.label)) },
                                        onClick = {
                                            appearance = option
                                            preferences.edit().putString("appearance", option.name).apply()
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = {
                        terminal.runCommand("showcase")
                        state.showKeyboard()
                    }) { Text(stringResource(R.string.run_example)) }
                    TextButton(onClick = { state.paste(); state.showKeyboard() }) {
                        Text(stringResource(R.string.paste))
                    }
                    TextButton(onClick = {
                        terminal.runCommand("clear")
                        state.showKeyboard()
                    }) { Text(stringResource(R.string.clear)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GhosttyTerminal(
                        terminal.session,
                        Modifier.fillMaxSize(),
                        state = state,
                        theme = theme,
                        darkColorScheme = dark,
                    )
                    GhosttyScrollIndicator(state, Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
                    GhosttyJumpToBottomButton(state, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
                GhosttyExtraKeysBar(state)
            }
        }
        GhosttyDialogs(state)
    }
}
