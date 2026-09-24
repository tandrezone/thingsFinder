package app.thingsfinder.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import app.thingsfinder.AppContainer
import app.thingsfinder.ThingsFinderApp

/** Every data screen renders exactly one of these. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data object NotFound : UiState<Nothing>
    data class Error(val message: UiMessage) : UiState<Nothing>
}

/** A user-facing message that a ViewModel can build without a Context. */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList(), val isError: Boolean = false) {
    fun resolve(context: Context): String = context.getString(res, *args.toTypedArray())
}

fun msg(@StringRes res: Int, vararg args: Any) = UiMessage(res, args.toList())
fun errorMsg(@StringRes res: Int, vararg args: Any) = UiMessage(res, args.toList(), isError = true)

@Composable
fun UiMessage.text(): String = resolve(LocalContext.current)

/** ViewModel factory that hands the [AppContainer] to [create]. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as ThingsFinderApp
            return create(app.container) as T
        }
    }

/** Same as [containerFactory], for ViewModels that also keep state in a [SavedStateHandle]. */
inline fun <reified VM : ViewModel> savedStateFactory(crossinline create: (AppContainer, SavedStateHandle) -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as ThingsFinderApp
            return create(app.container, extras.createSavedStateHandle()) as T
        }
    }
