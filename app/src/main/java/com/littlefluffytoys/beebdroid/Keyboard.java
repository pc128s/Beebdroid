package com.littlefluffytoys.beebdroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;

import static com.littlefluffytoys.beebdroid.BeebKeys.BBCKEY_CTRL_MOD;

public class Keyboard extends TouchpadsView {

    // The height of a row of keys
    private static final int ROWHEIGHT_SMALL = 36;

    // Shift modes
    public static final int SHIFTMODE_NORMAL = 0;
    public static final int SHIFTMODE_ONCE = 1;
    public static final int SHIFTMODE_LOCKED = 2;
    public int shiftMode;
    public boolean shiftActuallyHeld;

    // Shift LED drawables
    Drawable ledOn, ledOff;

    static class KeyMap {
        static Map<String, Integer> unicodeMap = new HashMap<String, Integer>();

        KeyMap(String label, String top, float weight, int scancode, int flags) {
            this.label = label;
            this.top = top;
            this.weight = weight;
            this.scancode = scancode;
            this.flags = flags;
            if (label != null) {
                if (top != null)
                    unicodeMap.put(top, scancode | BeebKeys.BBCKEY_SHIFT_MOD); // e.g. * is shift-:
                else
                    unicodeMap.put(label.toUpperCase(), scancode | BeebKeys.BBCKEY_SHIFT_MOD);
                // Prefer 'lowercase' version, for backfill - @ and space etc.
                unicodeMap.put(label.toLowerCase(), scancode | BeebKeys.BBCKEY_ANTISHIFT_MOD);
            }
        }

        KeyMap(String label, String top, float weight, int scancode) {
            this(label, top, weight, scancode, 0);
        }

        KeyMap() {
            this(null, null, 0f, 0, 0);
        }

        String label, top;
        float weight;
        int scancode, flags;
    }

    public static int altKey(int keycode) {
        return 0;
    }

    public static int unicodeToBeebKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.isAltPressed()) {
            int beebKey = unicodeAltToBeebKey(event.getKeyCode(), event.isShiftPressed());
            if (beebKey != 0 ) return beebKey;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return BeebKeys.BBCKEY_DELETE;
            case KeyEvent.KEYCODE_SPACE: return BeebKeys.BBCKEY_SPACE;
            case KeyEvent.KEYCODE_ENTER: return BeebKeys.BBCKEY_ENTER;
            case KeyEvent.KEYCODE_TAB: return BeebKeys.BBCKEY_TAB;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                return BeebKeys.BBCKEY_ESCAPE;
            case KeyEvent.KEYCODE_DPAD_UP: return BeebKeys.BBCKEY_ARROW_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return BeebKeys.BBCKEY_ARROW_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return BeebKeys.BBCKEY_ARROW_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return BeebKeys.BBCKEY_ARROW_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return BeebKeys.BBCKEY_COPY;
        }
        if (event.isCtrlPressed()) {
            int unicodeChar = event.getUnicodeChar(KeyEvent.META_SHIFT_ON);
            String uni = new String(Character.toChars(unicodeChar));
            return KeyMap.unicodeMap.containsKey(uni) ? KeyMap.unicodeMap.get(uni) & 0xff | 0x400 : 0;
        }
        int unicodeChar = event.getUnicodeChar();
        if (!Character.isValidCodePoint(unicodeChar)) {
            return 0;
        }
        return pureUnicodeToScancode(new String(Character.toChars(unicodeChar)));
    }

    public static int pureUnicodeToScancode(String uni) {
        if (uni.length() == 0 ) return 0;
        int chr = (int)uni.charAt(0);
        int ctrlq = 0;
        if (chr < 32) {
            uni = Character.toString((char)(chr + 96));
            ctrlq = BBCKEY_CTRL_MOD;
        }
        int sc = KeyMap.unicodeMap.containsKey(uni) ? KeyMap.unicodeMap.get(uni) | ctrlq : 0;
        Log.i("Keyboard", "uni: " + uni + " (" + chr + " | " + Integer.toHexString(ctrlq) + ") = sc " + sc + "=" + Integer.toHexString(sc));
        return sc;
    }

    public static int unicodeAltToBeebKey(int keyCode, boolean shiftPressed) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_C: return BeebKeys.BBCKEY_CAPS;
            case KeyEvent.KEYCODE_S: return BeebKeys.BBCKEY_SHIFTLOCK;
            case KeyEvent.KEYCODE_GRAVE: return BeebKeys.BBCKEY_ESCAPE;
            case KeyEvent.KEYCODE_F10: return BeebKeys.BBCKEY_BREAK; // vs US mapping
            case KeyEvent.KEYCODE_2: return BeebKeys.BBCKEY_AT; // vs US mapping
            case KeyEvent.KEYCODE_3: // vs US mapping.
                if (shiftPressed) return BeebKeys.BBCKEY_UNDERSCORE;
                else return BeebKeys.BBCKEY_POUND; // vs US mapping.
                // Don't mind having extra copy keys. :)
            case KeyEvent.KEYCODE_SPACE: return BeebKeys.BBCKEY_COPY;
            case KeyEvent.KEYCODE_DEL: return BeebKeys.BBCKEY_COPY;
            case KeyEvent.KEYCODE_ALT_RIGHT: return BeebKeys.BBCKEY_COPY;
        }
        return 0;
    }

    public static List<KeyMap> keyboard;

    static {
        String quid = "\u00a3"; // £
        keyboard = Arrays.asList(
                    new KeyMap("TAB", null, 1f, BeebKeys.BBCKEY_TAB, 0),
                    new KeyMap("f0", null, 1f, BeebKeys.BBCKEY_F0, 1),
                    new KeyMap("f1", null, 1f, BeebKeys.BBCKEY_F1, 1),
                    new KeyMap("f2", null, 1f, BeebKeys.BBCKEY_F2, 1),
                    new KeyMap("f3", null, 1f, BeebKeys.BBCKEY_F3, 1),
                    new KeyMap("f4", null, 1f, BeebKeys.BBCKEY_F4, 1),
                    new KeyMap("f5", null, 1f, BeebKeys.BBCKEY_F5, 1),
                    new KeyMap("f6", null, 1f, BeebKeys.BBCKEY_F6, 1),
                    new KeyMap("f7", null, 1f, BeebKeys.BBCKEY_F7, 1),
                    new KeyMap("f8", null, 1f, BeebKeys.BBCKEY_F8, 1),
                    new KeyMap("f9", null, 1f, BeebKeys.BBCKEY_F9, 1),
                    new KeyMap("BREAK", null, 1f, BeebKeys.BBCKEY_BREAK, 0),
                    new KeyMap(),
                    new KeyMap("ESC", null, 1f, BeebKeys.BBCKEY_ESCAPE),
                    new KeyMap("1", "!", 1f, BeebKeys.BBCKEY_1),
                    new KeyMap("2", "\"", 1f, BeebKeys.BBCKEY_2),
                    new KeyMap("3", "#", 1f, BeebKeys.BBCKEY_3),
                    new KeyMap("4", "$", 1f, BeebKeys.BBCKEY_4),
                    new KeyMap("5", "%", 1f, BeebKeys.BBCKEY_5),
                    new KeyMap("6", "&", 1f, BeebKeys.BBCKEY_6),
                    new KeyMap("7", "'", 1f, BeebKeys.BBCKEY_7),
                    new KeyMap("8", "(", 1f, BeebKeys.BBCKEY_8),
                    new KeyMap("9", ")", 1f, BeebKeys.BBCKEY_9),
                    new KeyMap("0", null, 1f, BeebKeys.BBCKEY_0),
                    new KeyMap("-", "=", 1f, BeebKeys.BBCKEY_MINUS),
                    new KeyMap("^", "~", 1f, BeebKeys.BBCKEY_CARET),
                    new KeyMap("\\", "|", 1f, BeebKeys.BBCKEY_BACKSLASH),
                    new KeyMap(),
                    new KeyMap("Q", null, 1f, BeebKeys.BBCKEY_Q),
                    new KeyMap("W", null, 1f, BeebKeys.BBCKEY_W),
                    new KeyMap("E", null, 1f, BeebKeys.BBCKEY_E),
                    new KeyMap("R", null, 1f, BeebKeys.BBCKEY_R),
                    new KeyMap("T", null, 1f, BeebKeys.BBCKEY_T),
                    new KeyMap("Y", null, 1f, BeebKeys.BBCKEY_Y),
                    new KeyMap("U", null, 1f, BeebKeys.BBCKEY_U),
                    new KeyMap("I", null, 1f, BeebKeys.BBCKEY_I),
                    new KeyMap("O", null, 1f, BeebKeys.BBCKEY_O),
                    new KeyMap("P", null, 1f, BeebKeys.BBCKEY_P),
                    new KeyMap("@", null, 1f, BeebKeys.BBCKEY_AT),
                    new KeyMap("[", "{", 1f, BeebKeys.BBCKEY_BRACKET_LEFT_SQ),
                    new KeyMap("_", quid, 1f, BeebKeys.BBCKEY_UNDERSCORE),  // _ and £
                    new KeyMap(),
                    new KeyMap("A", null, 1f, BeebKeys.BBCKEY_A),
                    new KeyMap("S", null, 1f, BeebKeys.BBCKEY_S),
                    new KeyMap("D", null, 1f, BeebKeys.BBCKEY_D),
                    new KeyMap("F", null, 1f, BeebKeys.BBCKEY_F),
                    new KeyMap("G", null, 1f, BeebKeys.BBCKEY_G),
                    new KeyMap("H", null, 1f, BeebKeys.BBCKEY_H),
                    new KeyMap("J", null, 1f, BeebKeys.BBCKEY_J),
                    new KeyMap("K", null, 1f, BeebKeys.BBCKEY_K),
                    new KeyMap("L", null, 1f, BeebKeys.BBCKEY_L),
                    new KeyMap(";", "+", 1f, BeebKeys.BBCKEY_SEMICOLON),
                    new KeyMap(":", "*", 1f, BeebKeys.BBCKEY_COLON),
                    new KeyMap("]", "}", 1f, BeebKeys.BBCKEY_BRACKET_RIGHT_SQ),
                    new KeyMap("\u2190", null, 1f, BeebKeys.BBCKEY_ARROW_LEFT),
                    new KeyMap("\u2192", null, 1f, BeebKeys.BBCKEY_ARROW_RIGHT),
                    new KeyMap(),
                    new KeyMap("Z", null, 1f, BeebKeys.BBCKEY_Z),
                    new KeyMap("X", null, 1f, BeebKeys.BBCKEY_X),
                    new KeyMap("C", null, 1f, BeebKeys.BBCKEY_C),
                    new KeyMap("V", null, 1f, BeebKeys.BBCKEY_V),
                    new KeyMap("B", null, 1f, BeebKeys.BBCKEY_B),
                    new KeyMap("N", null, 1f, BeebKeys.BBCKEY_N),
                    new KeyMap("M", null, 1f, BeebKeys.BBCKEY_M),
                    new KeyMap(",", "<", 1f, BeebKeys.BBCKEY_COMMA),
                    new KeyMap(".", ">", 1f, BeebKeys.BBCKEY_PERIOD),
                    new KeyMap("/", "?", 1f, BeebKeys.BBCKEY_SLASH),
                    // NOTE: ` is ASCII96, but BBC96 is £.
                    // So... should be shift-_ but its location is usually BBC Escape.
                    // BUT Escape is 'back' on Android, and a pita. So argh, Grave doesn't exist.
                    new KeyMap("`", null, 0f, BeebKeys.BBCKEY_ESCAPE),
                    new KeyMap("\u2191", null, 1f, BeebKeys.BBCKEY_ARROW_UP),
                    new KeyMap("\u2193", null, 1f, BeebKeys.BBCKEY_ARROW_DOWN),
                    new KeyMap(),
                    new KeyMap("SHIFT", null, 2f, BeebKeys.BBCKEY_SHIFT_MOD),
                    new KeyMap("CTRL", null, 1f, BeebKeys.BBCKEY_CTRL),
                    new KeyMap(" ", null, 4f, BeebKeys.BBCKEY_SPACE),
                    new KeyMap("DEL", null, 1f, BeebKeys.BBCKEY_DELETE),
                    new KeyMap("COPY", null, 1f, BeebKeys.BBCKEY_COPY),
                    new KeyMap("RETURN", null, 2f, BeebKeys.BBCKEY_ENTER)
                    );
    }

    // The keyboard keys, arranged in rows
    public ArrayList<KeyRow> rows = new ArrayList<KeyRow>();

    public Keyboard(Context context, AttributeSet attrs) {
        super(context, attrs);

        ledOff = context.getResources().getDrawable(R.drawable.led_off);
        ledOn = context.getResources().getDrawable(R.drawable.led_on);

        addRow();
        Iterator<KeyMap> keyit = keyboard.iterator();
        while (keyit.hasNext()) {
            KeyMap key = keyit.next();
            if (key.label == null) {
                addRow();
            } else {
                if (key.weight > 0) {
                    Key k = add(key.label, key.top, key.weight, key.scancode, key.flags);
                    if (key.label.equals("SHIFT")) addShiftMagic(k);
                }
            }
        }
    }

    private void addShiftMagic(Key k) {
        k.listener = new KeyListener() {
            @Override
            public void onKeyPressed(boolean pressed) {
                shiftActuallyHeld = pressed;
                if (pressed) {
                    shiftMode = SHIFTMODE_ONCE;
                    shiftPressed = true;
                }
                else {
                    if (shiftMode == SHIFTMODE_NORMAL) {
                        shiftPressed = false;
                    }
                }
                invalidate();
            }
        };
    }

    public Key add(String label, String labelTop, float weight, int scancode, int flags) {
        KeyRow row = rows.get(rows.size() - 1);
        Key pad = new Key();
        pad.label = label;
        pad.labelTop = labelTop;
        pad.scancode = scancode;
        pad.layout_width = 0;
        pad.layout_weight = weight;
        pad.flags = flags;
        row.keys.add(pad);
        allkeys.add(pad);
        return pad;
    }

    public Key add(String label, String labelTop, float weight, int scancode) {
        return add(label, labelTop, weight, scancode, 0);
    }

    @Override
    public void defaultOnKeyPressed(Key key, boolean pressed) {
        if (shiftPressed && !shiftActuallyHeld && shiftMode == SHIFTMODE_ONCE) {
            shiftPressed = false;
            invalidate();
        }
        if (shiftActuallyHeld) {
            shiftMode = SHIFTMODE_NORMAL;
        }
    }

    public void addRow() {
        rows.add(new KeyRow());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        float rowheight = (b - t) / 6f;//ROWHEIGHT_SMALL * (getContext().getResources().getDisplayMetrics().heightPixels / 480f);

        float y = 0;
        for (KeyRow row : rows) {
            row.layout(r - l, y, y + rowheight + Beebdroid.dp(2));
            y += rowheight;//- Beebdroid.dp(2);
        }

        b -= t;
        t = 0;
        int o = (int) Beebdroid.dp(8);
        int d = (int) Beebdroid.dp(10);
        ledOn.setBounds(o, b - (d + o), d + o, b - o);
        ledOff.setBounds(o, b - (d + o), d + o, b - o);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        Drawable led = shiftPressed ? ledOn : ledOff;
        led.draw(canvas);
    }



    //
    // KeyRow
    //
    public static class KeyRow {
        public ArrayList<Key> keys = new ArrayList<Key>();
        public void layout(int width, float ftop, float fbottom) {
            float sumweights = 0;
            float sumwidths = 0;
            for (Key key : keys) {
                sumweights += key.layout_weight;
                sumwidths += key.layout_width;
            }
            float x = 0;
            float excess_space = width - Beebdroid.dp(sumwidths);
            float excess_unit = (sumweights == 0) ? 0 : (excess_space / sumweights);
            for (Key key : keys) {
                float keywidth = Beebdroid.dp(key.layout_width) + excess_unit * key.layout_weight;
                key.bounds = new RectF(x, ftop, x + keywidth, fbottom);
                x += keywidth;
            }
        }
    }
}
