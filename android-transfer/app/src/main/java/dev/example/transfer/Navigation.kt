package dev.example.transfer

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.example.transfer.ui.main.MainScreen

/**
 * 目前只有一个页面。要加第二个页面时：
 *  1. 在 NavigationKeys.kt 里加一个 @Serializable data object/class : NavKey
 *  2. 在下面的 entryProvider 里加 entry<你的Key> { ... }
 *  3. 从 MainScreen 回调里 backStack.add(你的Key)
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(modifier = Modifier.padding(16.dp))
        }
      },
  )
}
