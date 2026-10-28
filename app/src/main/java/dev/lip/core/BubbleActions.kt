package dev.lip.core

/** Stable Insert control: focus/state changes may disable it, never reinterpret it as Copy. */
object BubbleActions {
    fun insertEnabled(ready:Boolean,insertable:Boolean,target:Boolean)=ready&&insertable&&target
}
