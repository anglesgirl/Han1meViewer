package io.github.daisukikaffuchino.han1meviewer.util

import androidx.fragment.app.Fragment
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity

fun Fragment.openVideo(code: String) {
    (activity as? MainActivity)?.showVideoDetailFragment(code)
}
