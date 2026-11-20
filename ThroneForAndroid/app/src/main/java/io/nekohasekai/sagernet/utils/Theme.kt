package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

object Theme {

    fun apply(context: Context) = context.setTheme(getTheme())

    fun applyDialog(context: Context) = context.setTheme(R.style.Theme_SagerNet_Dialog_Pink_SSR)

    fun getTheme(): Int = R.style.Theme_SagerNet_Pink_SSR

    fun usingNightMode(): Boolean =
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
