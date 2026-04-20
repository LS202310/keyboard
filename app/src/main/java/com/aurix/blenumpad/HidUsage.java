package com.aurix.blenumpad;

/**
 * HID Keyboard Usage IDs
 * 对应 ESP32 hidh_callback 里的 case 值
 */
public class HidUsage {

    // ========== Modifier Keys (Byte 0) ==========
    public static final byte MOD_NONE       = 0x00;
    public static final byte MOD_LEFT_CTRL  = 0x01;
    public static final byte MOD_LEFT_SHIFT = 0x02;
    public static final byte MOD_LEFT_ALT   = 0x04;
    public static final byte MOD_LEFT_GUI   = 0x08;
    public static final byte MOD_RIGHT_CTRL = 0x10;
    public static final byte MOD_RIGHT_SHIFT= 0x20;
    public static final byte MOD_RIGHT_ALT  = 0x40;
    public static final byte MOD_RIGHT_GUI  = (byte)0x80;

    // ========== Numpad Keys ==========
    public static final byte KEY_NUMLOCK    = 0x53; // 83
    public static final byte KEY_KP_SLASH   = 0x54; // 84  /
    public static final byte KEY_KP_STAR    = 0x55; // 85  *
    public static final byte KEY_KP_MINUS   = 0x56; // 86  -
    public static final byte KEY_KP_PLUS    = 0x57; // 87  +
    public static final byte KEY_KP_ENTER   = 0x58; // 88  Enter
    public static final byte KEY_KP_1       = 0x59; // 89
    public static final byte KEY_KP_2       = 0x5A; // 90
    public static final byte KEY_KP_3       = 0x5B; // 91
    public static final byte KEY_KP_4       = 0x5C; // 92
    public static final byte KEY_KP_5       = 0x5D; // 93
    public static final byte KEY_KP_6       = 0x5E; // 94
    public static final byte KEY_KP_7       = 0x5F; // 95
    public static final byte KEY_KP_8       = 0x60; // 96
    public static final byte KEY_KP_9       = 0x61; // 97
    public static final byte KEY_KP_0       = 0x62; // 98
    public static final byte KEY_KP_DOT     = 0x63; // 99  .

    // ========== Standard Keys ==========
    public static final byte KEY_BACKSPACE  = 0x2A; // 42

    // ========== HID Report ==========
    // 标准8字节 keyboard report:
    // [0] modifier, [1] reserved(0), [2..7] keycodes
    public static byte[] makeReport(byte modifier, byte keycode) {
        return new byte[]{ modifier, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00 };
    }

    // 释放所有键（key up）
    public static byte[] makeEmptyReport() {
        return new byte[]{ 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
    }
}
