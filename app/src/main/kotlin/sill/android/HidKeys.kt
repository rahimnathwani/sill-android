package sill.android

import android.view.KeyEvent
import sill.protocol.Modifiers

/** A hardware keyboard's keys as USB HID usages (page 7), which the Mac maps to its own. */
object HidKeys {
    private val map: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 0x04 + i)
        put(KeyEvent.KEYCODE_1, 0x1E); put(KeyEvent.KEYCODE_2, 0x1F); put(KeyEvent.KEYCODE_3, 0x20)
        put(KeyEvent.KEYCODE_4, 0x21); put(KeyEvent.KEYCODE_5, 0x22); put(KeyEvent.KEYCODE_6, 0x23)
        put(KeyEvent.KEYCODE_7, 0x24); put(KeyEvent.KEYCODE_8, 0x25); put(KeyEvent.KEYCODE_9, 0x26)
        put(KeyEvent.KEYCODE_0, 0x27)
        put(KeyEvent.KEYCODE_ENTER, 0x28); put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_ESCAPE, 0x29); put(KeyEvent.KEYCODE_DEL, 0x2A); put(KeyEvent.KEYCODE_TAB, 0x2B)
        put(KeyEvent.KEYCODE_SPACE, 0x2C); put(KeyEvent.KEYCODE_MINUS, 0x2D); put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31); put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34); put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36); put(KeyEvent.KEYCODE_PERIOD, 0x37); put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, 0x3A + i)
        put(KeyEvent.KEYCODE_INSERT, 0x49); put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A); put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C); put(KeyEvent.KEYCODE_MOVE_END, 0x4D); put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F); put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51); put(KeyEvent.KEYCODE_DPAD_UP, 0x52)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 0xE0); put(KeyEvent.KEYCODE_SHIFT_LEFT, 0xE1)
        put(KeyEvent.KEYCODE_ALT_LEFT, 0xE2); put(KeyEvent.KEYCODE_META_LEFT, 0xE3)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 0xE4); put(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xE5)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 0xE6); put(KeyEvent.KEYCODE_META_RIGHT, 0xE7)
    }

    fun usage(keyCode: Int): Int? = map[keyCode]

    /**
     * Modifiers as the Mac's flags. A PC keyboard has Ctrl where a Mac has ⌘ and an Android
     * keyboard's Meta key is rare, so by default Ctrl sends ⌘ (Ctrl-C copies on the Mac) and Meta
     * sends ⌃; [ctrlIsCommand] false keeps them literal.
     */
    fun modifiers(meta: Int, ctrlIsCommand: Boolean): Long {
        var m = 0L
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or Modifiers.SHIFT
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or Modifiers.OPTION
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val metaKey = meta and KeyEvent.META_META_ON != 0
        if (ctrlIsCommand) {
            if (ctrl) m = m or Modifiers.COMMAND
            if (metaKey) m = m or Modifiers.CONTROL
        } else {
            if (ctrl) m = m or Modifiers.CONTROL
            if (metaKey) m = m or Modifiers.COMMAND
        }
        return m
    }

    /** With [ctrlIsCommand], Ctrl's own keys are ⌘'s and Meta's are ⌃'s, as [modifiers] says. */
    fun swapped(usage: Int, ctrlIsCommand: Boolean): Int = if (!ctrlIsCommand) usage else when (usage) {
        0xE0 -> 0xE3; 0xE3 -> 0xE0; 0xE4 -> 0xE7; 0xE7 -> 0xE4
        else -> usage
    }
}
