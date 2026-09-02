package app.gains.ui.nav

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Home : Screen
    data object Exercises : Screen
    data object Volume : Screen
    data object Body : Screen
    data object Consistency : Screen
    data object Settings : Screen
    data object Import : Screen
    data class ExerciseDetail(val exerciseId: String) : Screen
}

enum class Tab(val label: String, val root: Screen) {
    HOME("Home", Screen.Home),
    EXERCISES("Lifts", Screen.Exercises),
    VOLUME("Volume", Screen.Volume),
    BODY("Body", Screen.Body),
    CONSISTENCY("Calendar", Screen.Consistency),
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        if (current != screen) stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun switchTab(tab: Tab) {
        stack.clear()
        stack.add(tab.root)
    }

    val currentTab: Tab? get() = Tab.entries.firstOrNull { it.root == stack.first() }
}
