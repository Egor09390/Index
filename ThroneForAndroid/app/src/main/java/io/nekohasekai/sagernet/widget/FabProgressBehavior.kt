package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

class FabProgressBehavior(context: Context, attrs: AttributeSet?) :
    CoordinatorLayout.Behavior<CircularProgressIndicator>(context, attrs) {
    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        dependency: View,
    ): Boolean {
        return dependency.id == (child.layoutParams as CoordinatorLayout.LayoutParams).anchorId
    }

    override fun onMeasureChild(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ): Boolean {
        val anchorId = (child.layoutParams as CoordinatorLayout.LayoutParams).anchorId
        val button = parent.getDependencies(child).single { it.id == anchorId }
        child.indicatorSize = button.measuredHeight + child.trackThickness
        return false
    }
}
