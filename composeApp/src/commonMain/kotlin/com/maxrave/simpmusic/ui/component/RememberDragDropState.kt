package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onSwap: (Int, Int) -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val state =
        remember(lazyListState) {
            DragDropState(
                state = lazyListState,
                onSwap = onSwap,
                scope = scope,
            )
        }
    return state
}

fun LazyListState.getVisibleItemInfoFor(absoluteIndex: Int): LazyListItemInfo? =
    this
        .layoutInfo
        .visibleItemsInfo
        .getOrNull(
            absoluteIndex -
                this.layoutInfo.visibleItemsInfo
                    .first()
                    .index,
        )

val LazyListItemInfo.offsetEnd: Int
    get() = this.offset + this.size

@OptIn(ExperimentalFoundationApi::class)
@ExperimentalFoundationApi
@Composable
fun LazyItemScope.DraggableItem(
    dragDropState: DragDropState,
    index: Int,
    modifier: Modifier,
    content: @Composable ColumnScope.(isDragging: Boolean) -> Unit,
) {
    val current: Float by animateFloatAsState(dragDropState.draggingItemOffset)
    val previous: Float by animateFloatAsState(dragDropState.previousItemOffset.value)
    val dragging = index == dragDropState.currentIndexOfDraggedItem
    val draggingModifier =
        if (dragging) {
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = current
                }
        } else if (index == dragDropState.previousIndexOfDraggedItem) {
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    translationY = previous
                }
        } else {
            Modifier.animateItem(
                fadeInSpec = null,
                fadeOutSpec = null,
                placementSpec = tween(easing = FastOutLinearInEasing),
            )
        }
    Column(modifier = modifier.then(draggingModifier)) {
        content(dragging)
    }
}

class DragDropState internal constructor(
    val state: LazyListState,
    private val scope: CoroutineScope,
    private val onSwap: (Int, Int) -> Unit,
) {
    private var draggedDistance by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)
    internal val draggingItemOffset: Float
        get() =
            draggingItemLayoutInfo?.let { item ->
                draggingItemInitialOffset + draggedDistance - item.offset
            } ?: 0f
    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() =
            state.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentIndexOfDraggedItem }

    internal var previousIndexOfDraggedItem by mutableStateOf<Int?>(null)
        private set
    internal var previousItemOffset = Animatable(0f)
        private set

    // used to obtain initial offsets on drag start
    private var initiallyDraggedElement by mutableStateOf<LazyListItemInfo?>(null)

    var currentIndexOfDraggedItem by mutableStateOf<Int?>(null)

    val draggedFromIndex: Int?
        get() = initiallyDraggedElement?.index

    /**
     * 現在の挿入先。ドラッグ中に毎回評価され、元位置へ戻った場合は明示的に null になる。
     * UIはこれを使って挿入ラインを描画する。
     */
    val dropTargetIndex: Int?
        get() = currentSwapFromTo?.let { (from, to) -> if (from == to) null else to }

    private val initialOffsets: Pair<Int, Int>?
        get() = initiallyDraggedElement?.let { Pair(it.offset, it.offsetEnd) }

    private val currentElement: LazyListItemInfo?
        get() =
            currentIndexOfDraggedItem?.let {
                state.getVisibleItemInfoFor(absoluteIndex = it)
            }

    /**
     * ドラッグ中の移動候補。
     * onDrag の都度再評価され、元位置へ戻った場合は from == to として保持するのではなく null へ戻す。
     * これにより「移動→同じジェスチャで元位置へ戻す」操作で API 呼び出し・Toast・挿入ラインをすべて抑止する。
     */
    private var currentSwapFromTo by mutableStateOf<Pair<Int, Int>?>(null)

    fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item -> offset.y.toInt() in item.offset..(item.offset + item.size) }
            ?.let(::startDragging)
    }

    fun onDragStart(index: Int) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.let(::startDragging)
    }

    private fun startDragging(item: LazyListItemInfo) {
        currentIndexOfDraggedItem = item.index
        initiallyDraggedElement = item
        draggingItemInitialOffset = item.offset
        currentSwapFromTo = null
    }

    fun onDragInterrupted(end: Boolean = false) {
        // ドラッグ終了時の候補を確定する。
        // from == to の候補は「元位置へ戻った」状態なので移動も API 呼び出しも行わない。
        val pending = currentSwapFromTo
        if (end && pending != null) {
            val (from, to) = pending
            if (from != to && from >= 0 && to >= 0) {
                Logger.w("QueueBottomSheet", "onDragInterrupted commit: $from -> $to")
                onSwap(from, to)
                currentIndexOfDraggedItem = to
            } else {
                Logger.w("QueueBottomSheet", "onDragInterrupted cancelled at origin: $from")
            }
        }
        currentSwapFromTo = null
        if (currentIndexOfDraggedItem != null) {
            previousIndexOfDraggedItem = currentIndexOfDraggedItem
            val startOffset = draggingItemOffset
            scope.launch {
                previousItemOffset.snapTo(startOffset)
                previousItemOffset.animateTo(
                    0f,
                    tween(easing = FastOutLinearInEasing),
                )
                previousIndexOfDraggedItem = null
            }
        }
        draggingItemInitialOffset = 0
        draggedDistance = 0f
        currentIndexOfDraggedItem = null
        initiallyDraggedElement = null
    }

    fun onDrag(offset: Offset) {
        draggedDistance += offset.y

        initialOffsets?.let { (topOffset, bottomOffset) ->
            val startOffset = topOffset + draggedDistance
            val endOffset = bottomOffset + draggedDistance
            val draggedCenter = (startOffset + endOffset) / 2f

            // The dragged row is back inside its original slot. Clear the last target
            // immediately so releasing here becomes a true no-op.
            if (draggedCenter in topOffset.toFloat()..bottomOffset.toFloat()) {
                currentSwapFromTo = null
                return
            }

            currentElement?.let { hovered ->
                val target =
                    state.layoutInfo.visibleItemsInfo
                    .filterNot { item -> item.offsetEnd < startOffset || item.offset > endOffset || hovered.index == item.index }
                    .firstOrNull { item ->
                        val delta = (startOffset - hovered.offset)
                        when {
                            delta > 0 -> (endOffset > item.offsetEnd)
                            else -> (startOffset < item.offset)
                        }
                    }
                target?.let { item ->
                    currentIndexOfDraggedItem?.let { current ->
                        currentSwapFromTo = Pair(current, item.index)
                    }
                }
            }
        }
    }

    fun checkForOverScroll(): Float {
        return initiallyDraggedElement?.let {
            val startOffset = it.offset + draggedDistance
            val endOffset = it.offsetEnd + draggedDistance
            return@let when {
                draggedDistance > 0 -> (endOffset - state.layoutInfo.viewportEndOffset + 50f).takeIf { diff -> diff > 0 }
                draggedDistance < 0 -> (startOffset - state.layoutInfo.viewportStartOffset - 50f).takeIf { diff -> diff < 0 }
                else -> null
            }
        } ?: 0f
    }
}
