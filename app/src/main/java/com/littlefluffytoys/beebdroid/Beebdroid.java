package com.littlefluffytoys.beebdroid;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

import com.littlefluffytoys.beebdroid.ControllerInfo.TriggerAction;

import common.Utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.AlignmentSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Rect;

import static android.text.Layout.Alignment.ALIGN_NORMAL;
import static android.text.Layout.Alignment.ALIGN_OPPOSITE;
import static android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
import static com.littlefluffytoys.beebdroid.BeebKeys.*;
import static com.littlefluffytoys.beebdroid.Keyboard.unicodeToBeebKey;


public class Beebdroid extends Activity {
    private static final String TAG = "Beebdroid";
    // The emulator keypresses from the physical keyboard are only 1-2ms and
    // don't register, so observe this and delay the key up.
    // Ideally, we'd notice keyrepeat before this time and cancel the scheduled key up.
    public static final int MIN_KEY_DOWNUP_MS = 50; //10;
    public static final int INTER_AUTO_KEY_MS = 10;
    public static boolean use25fps = false;
    private EditText rs423printer;
    private EditText rs423keyboard;
    private InputMethodManager imm;
    private File captureFile;
    private int rs423PendingInjectByte;

    private enum KeyboardState {SCREEN_KEYBOARD, CONTROLLER, BLUETOOTH_KBD, RS423_CONSOLE}

    static int locks = 0;

    Model model;
    DiskInfo diskInfo;
    int last_trigger;
    int keyboardTextWait;
    AudioTrack audio;
    ByteBuffer audiobuff;
    static Handler handler = new Handler();
    BeebView beebView;
    Keyboard keyboard;
    ControllerView controller;
    List<String> keyboardTextEvents = new ArrayList<String>();
    KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    int fps, skipped;
    TextView tvFps;
    private KeyboardState keyboardShowing = KeyboardState.SCREEN_KEYBOARD;
    ControllerInfo currentController;
    boolean isXperiaPlay;

    // Load our native library
    static {
        System.loadLibrary("bbcmicro");
    }

    byte[] printerBuf = new byte[bbcOfferingRs423(null)];
    byte[] keyboardBuf = new byte[1];

    // JNI interface
    public native void bbcInit(ByteBuffer mem, ByteBuffer roms, ByteBuffer audiob, int flags);

    public native void bbcBreak(int flags);

    public native void bbcExit();

    public native int bbcRun();

    public native int bbcInitGl(int width, int height);

    public native void bbcLoadDisc(ByteBuffer disc, int autoboot);

    public native void bbcSetTriggers(short[] pc_triggers);

    public static native void bbcKeyEvent(int scancode, int shift, int down);

    public native int bbcSerialize(byte[] buffer);

    public native void bbcDeserialize(byte[] buffer);

    public native int bbcGetThumbnail(Bitmap bmp);

    public native int bbcGetLocks();

    public native void bbcPushAdc(int x1, int y1, int x2, int y2);

    public native int bbcOfferingRs423(byte[] b);

    public native int bbcAcceptedRs423(byte[] b);

    long time_fps;

    // TODO: Split these up and only trigger them when they independently have something. Maybe from callbacks like video?
    // Also, looks like they don't run faster than 50Hz?
    // Also, sometimes characters get doubled - is that Android or BBC?
    private Runnable runRS423 = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.uptimeMillis();
            handler.postAtTime(runRS423, now + 20); // buffered, hopefully.
            doRS423TxRx();
        }

        public void doRS423TxRx() {
            if (captureStream != null || rs423printer != null) {
                int len = bbcOfferingRs423(printerBuf);
                for (int i = 0; i < len; i++) {
                    int c = printerBuf[i];
                    // Alas, can't 'delete' mistakes in the printer: 127 is only sent with VDU1,127
                    if (c == 13) c = 10;
                    if (captureStream != null) {
                        try {
                            captureStream.write((byte)c);
                        } catch (IOException e) {
                            Toast.makeText(Beebdroid.this, "Capture failed: " + e, Toast.LENGTH_LONG).show();
                            try {
                                captureStream.close();
                            } catch (IOException ioException) {
                                ;
                            }
                            captureStream = null;
                            captureFile = null;
                        }
                    } else {
                        rs423printer.append(String.valueOf((char) c));
                    }
                }
            }
            if (injectStream != null) {
                if (rs423PendingInjectByte == -1) {
                    try {
                        rs423PendingInjectByte = injectStream.read();
                    } catch (IOException e) {
                        rs423PendingInjectByte = -1;
                        Toast.makeText(Beebdroid.this, "Exception while injecting " + injectFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                }
                while (rs423PendingInjectByte >= 0 && sentRS423Char((char)rs423PendingInjectByte)) {
                    try {
                        rs423PendingInjectByte = injectStream.read();
                    } catch (IOException e) {
                        rs423PendingInjectByte = -1;
                        Toast.makeText(Beebdroid.this, "Exception while injecting " + injectFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                }
                if (rs423PendingInjectByte == -1) {
                    try {
                        injectStream.close();
                    } catch (IOException ioException) {
                        Toast.makeText(Beebdroid.this, "Exception while closing " + injectFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                    injectStream = null;
                    injectFile = null;
                    return;
                }
            } else {
                while (rs423keyboard != null &&
                        rs423keyboard.getText().length() > 0 &&
                        sentRS423Char(rs423keyboard.getText().charAt(0))) {
                    rs423keyboard.getText().delete(0, 1);
                }
            }
        }

        public boolean sentRS423Char(char c) {
            if (c == 10) c = 13;
            keyboardBuf[0] = (byte)c;
            return bbcAcceptedRs423(keyboardBuf) == 1;
        }
    };

    // This runnable drives the native emulation code
    private Runnable runInt50 = new Runnable() {
        @Override
        public void run() {

            // Execute 1/50th of a second of BBC micro!
            long now = android.os.SystemClock.uptimeMillis();
            handler.postAtTime(runInt50, now + 20);
            if (beebView.gl == null) {
                if (beebView.egl == null) { // no surface yet
                    return;
                }
                beebView.initgl();
                bbcInitGl(beebView.width, beebView.height);
            }
            int trigger = bbcRun();

            // Handle trigger events
            if (controller.controllerInfo != null) {
                final List<TriggerAction> triggers = controller.controllerInfo.triggers;
                if (trigger != last_trigger && triggers != null) {
                    Log.d("Trigger!", "PC hit trigger " + trigger);
                    last_trigger = trigger;
                    if (triggers.size() >= trigger) {
                        onTriggerFired(controller.controllerInfo.triggers.get(trigger - 1));
                    }
                }
            }

            int ic32 = bbcGetLocks();
            if (locks != ic32) {
                String s = "NoLk";
                if ((ic32 & 64) == 0) s = "CapsLk";
                if ((ic32 & 128) == 0) s = "ShLk";
                ((TextView) findViewById(R.id.lock)).setText(s);
                locks = ic32;
            }

            // Automatic keyboard text
            if (keyboardTextWait > 0) {
                keyboardTextWait--;
            } else {
                if (keyboardTextEvents.size() > 0) {
                    String next = keyboardTextEvents.remove(0);
                    Log.i(TAG, "keyboard text event ='" + next + "'");
                    final int scancode = Keyboard.pureUnicodeToScancode(next);
                    keyboardTextWait = MIN_KEY_DOWNUP_MS + INTER_AUTO_KEY_MS;
                    if (scancode != 0) {
                        bbcKeyEvent(scancode, 0, 1);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                bbcKeyEvent(scancode, 0, 0);
                                keyboardTextWait = 1;
                            }
                        }, keyboardTextWait - INTER_AUTO_KEY_MS);
                    }
                }
            }
        }
    };

    final HashMap<Integer, Runnable> delayedUp = new HashMap<Integer, Runnable>();

//    static long thing = 0;

    boolean onKeyUpDown(final int keycode, KeyEvent event, final boolean isDown) {
//        Log.e(TAG, "updown " + (thing - event.getEventTime()) + " " + event.toString());
//        thing = event.getEventTime();

        Utils.setVisible(this, R.id.info, isDown);

        if (keyboardShowing != KeyboardState.BLUETOOTH_KBD && isXperiaPlay && onXperiaKey(keycode, event, isDown ? 1 : 0)) {
            return true;
        }

        // Keyboard toggle in all modules.
        if (isDown
                && event.isAltPressed()
                && (event.getUnicodeChar(0) == 'x' || event.getUnicodeChar(0) == 'k')) {
            toggleKeyboard();
            if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {
                beebView.requestFocus();
            }
            return true;
        }

        if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {
            if (keycode == KeyEvent.KEYCODE_MENU) return false; // Someone else's problem!
            int scancode = causeBBCKeyActionFromUnicode(event);
            if (isDown) showInfo(event, scancode);
            if (scancode != 0) return true;
            return false;
        }

        // If pressed 'back' while game loaded, reset the emulator rather than exit the app
        if (keycode == KeyEvent.KEYCODE_BACK && isDown) {
            if (diskInfo != null) {
                //bbcInit(model.mem, model.roms, audiobuff, model.info.flags);
                bbcBreak(0);
                diskInfo = null;
                SavedGameInfo.current = null;
                showKeyboard(KeyboardState.SCREEN_KEYBOARD);
                return true;
            }
        }

        if (keycode == KeyEvent.KEYCODE_SHIFT_LEFT || keycode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            shiftDown = isDown;
        }

        final int scancode = lookup(keycode, event);
        if (isDown || event.getDownTime() - event.getEventTime() > MIN_KEY_DOWNUP_MS) {
            unscheduleKeyup(keycode);
            bbcKeyEvent(scancode, shiftDown ? 1 : 0, isDown ? 1 : 0);
        } else {
            scheduleKeyup(keycode, new Runnable() {
                @Override
                public void run() {
                    synchronized (delayedUp) {
                        if (delayedUp.remove(keycode) == this)
                            bbcKeyEvent(scancode, shiftDown ? 1 : 0, isDown ? 1 : 0);
                    }
                }
            });
        }
        if (isDown) showInfo(event, scancode);
        return false;
    }

    private void showInfo(KeyEvent event, int scancode) {
        int u0 = event.getUnicodeChar();
        int u1 = event.getUnicodeChar(KeyEvent.META_SHIFT_ON);
        TextView t = (TextView) this.findViewById(R.id.info);
        String text = uToStr(u0) + "=" + u0 +
                " " + uToStr(u1) + "=" + u1 +
                " " + Integer.toHexString(scancode) + "=inkey(-" + (0xff & scancode + 1) + ")" +
                "\n" + KeyEvent.keyCodeToString(event.getKeyCode());
        t.setText(text);
    }

    @Override
    public boolean onKeyDown(int keycode, KeyEvent event) {
        return onKeyUpDown(keycode, event, true) || super.onKeyDown(keycode, event);
    }

    @Override
    public boolean onKeyUp(int keycode, KeyEvent event) {
        return onKeyUpDown(keycode, event, false) || super.onKeyUp(keycode, event);
    }

    private String uToStr(int u0) {
        try {
            return u0 < 32 ? "" : new String(Character.toChars(u0));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "uToStr(" + u0 + ") threw " + e);
            return "";
        }
    }

    // Keep track of what caused a keydown in case shift shifts ; to : and messes up the key.
    HashMap<Integer, Integer> pushedKeycodes = new HashMap<Integer, Integer>();

    private int causeBBCKeyActionFromUnicode(final KeyEvent event) {
        final int keycode = event.getKeyCode();
        final boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        if (isDown) {
            unscheduleKeyup(keycode);
            int scancode = unicodeToBeebKey(event);
            if (scancode != 0) {
                pushedKeycodes.put(keycode, scancode);
                bbcKeyEvent(scancode, 0, 1);
            }
            return scancode;
        } else {
            final Integer scancode = pushedKeycodes.remove(keycode);
            if (scancode != null && scancode != 0) {
                if (event.getDownTime() - event.getEventTime() > MIN_KEY_DOWNUP_MS) {
                    // Probably don't care about unscheduling a keyup, but...
                    unscheduleKeyup(keycode);
                    bbcKeyEvent(scancode, 0, 0);
                } else {
                    scheduleKeyup(keycode, new Runnable() {
                        @Override
                        public void run() {
                            synchronized (delayedUp) {
                                if (delayedUp.remove(keycode) == this)
                                    bbcKeyEvent(scancode, 0, 0);
                            }
                        }
                    });
                }
                return scancode;
            }
            return 0;
        }
    }

    private void scheduleKeyup(int keycode, Runnable keyUp) {
        synchronized (delayedUp) {
            delayedUp.put(keycode, keyUp);
            handler.postDelayed(keyUp, MIN_KEY_DOWNUP_MS);
        }
    }

    private void unscheduleKeyup(int keycode) {
        synchronized (delayedUp) {
            Runnable keyUp = delayedUp.remove(keycode);
            if (keyUp != null) handler.removeCallbacks(keyUp);
        }
    }

    //
    // onXperiaKey
    //
    private boolean onXperiaKey(int keycode, KeyEvent event, int isDown) {
        if (keycode == KeyEvent.KEYCODE_BACK && !event.isAltPressed()) return false;
        ControllerInfo.KeyInfo info = controller.controllerInfo.keyinfosMappedByAndroidKeycode.get(keycode);
        if (info != null) {
            showInfo(event, info.scancode);
            bbcKeyEvent(info.scancode, shiftDown ? 1 : 0, isDown);
            return true;
        }
        return false;
    }

    private void onTriggerFired(TriggerAction triggerAction) {
        //if (triggerAction instanceof TriggerActionSetController) {
        //	TriggerActionSetController actionSetController = (TriggerActionSetController)triggerAction;
        //	setController(actionSetController.controllerInfo);
        //}
    }


    private void doFakeKeys(String text) {
        keyboardTextEvents = new ArrayList(Arrays.asList(text.split("")));
        Log.i(TAG, "doFakeKeys ='" + keyboardTextEvents + "'");
    }

    int adctick = 0;
    int updown = 0;
    long last_ms;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        use25fps = Build.DEVICE.equals("bravo");

        //Log.d("Build", "Its a " + Build.DEVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Create this directory so LOGGING can use it.
            File dir = getExternalFilesDir("");
            Log.i("Storage", dir.toString());
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }

        setContentView(R.layout.activity_beebdroid);

        DPI_MULT = getResources().getDisplayMetrics().density;
        DP_SCREEN_WIDTH = getResources().getDisplayMetrics().widthPixels;

        // Detect the Xperia Play, for which we do special magic
        isXperiaPlay =
                Build.DEVICE.equalsIgnoreCase("R800i")
                        || Build.DEVICE.equalsIgnoreCase("zeus")
        //        || Build.DEVICE.equalsIgnoreCase("tank") // Amazon Fire TV
        ;

        // Find UI bits and wire things up
        beebView = (BeebView) findViewById(R.id.beeb);
        keyboard = (Keyboard) findViewById(R.id.keyboard);
        keyboard.beebdroid = this;
        controller = (ControllerView) findViewById(R.id.controller);
        controller.beebdroid = this;

        imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        rs423printer = findViewById(R.id.rs423printer);
        if (rs423printer != null) {
            rs423printer.setKeyListener(null); // Prevent typing into the field
            rs423printer.setOnTouchListener( // Yes, yes... evil accessibility thing, but they can press 'back'
                    new View.OnTouchListener() {
                        @SuppressLint("ClickableViewAccessibility")
                        @Override
                        public boolean onTouch(View view, MotionEvent motionEvent) {
                            hideKeyboard(view);
                            return false;
                        }
                    });
        }
        rs423keyboard = findViewById(R.id.rs423keyboard);
        if (rs423keyboard != null) {
            rs423keyboard.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int i, KeyEvent keyEvent) {
                    if (keyEvent.getAction() == KeyEvent.ACTION_DOWN && keyEvent.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                        rs423keyboard.setText("\u007f");
                        // NOTE: Although this DOES delete on the BBC, it isn't passed to the printer without VDU1,127!
                        // So... put Beebview in the RS423 layout, and add buttons for turning serial input and output on/off independently.
                    }
                    return false;
                }
            });
        }

        enrichen(R.id.kb_bt_alt);
        enrichen(R.id.keyboard_help);

        // See if we're a previous instance of the same activity, or a totally fresh one
        Beebdroid prev = (Beebdroid) getLastNonConfigurationInstance();
        if (prev == null) {
            InstalledDisks.load(this);
            audiobuff = ByteBuffer.allocateDirect(2000 * 2);
            audio = new AudioTrack(AudioManager.STREAM_MUSIC, 31250,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    16384,
                    AudioTrack.MODE_STREAM);
            model = new Model();
            model.loadRoms(this, Model.SupportedModels[1]);
            bbcInit(model.mem, model.roms, audiobuff, 1);
            currentController = Controllers.DEFAULT_CONTROLLER;
            //if (UserPrefs.shouldShowSplashScreen(this)) {
            //	startActivity(new Intent(Beebdroid.this, AboutActivity.class));
            //}
            processDiskViaIntent();
        } else {
            model = prev.model;
            audio = prev.audio;
            audiobuff = prev.audiobuff;
            diskInfo = prev.diskInfo;
            last_trigger = prev.last_trigger;
            keyboardTextWait = prev.keyboardTextWait;
            keyboardTextEvents = prev.keyboardTextEvents;
            Log.i(TAG, "Prev text events ='" + keyboardTextEvents + "'");
            keyboardShowing = prev.keyboardShowing;
            currentController = prev.currentController;
            bbcInit(model.mem, model.roms, audiobuff, 0);
        }
        setController(currentController);
        showKeyboard(keyboardShowing);

        // Wire up the white buttons
        final ImageView btnInput = (ImageView) findViewById(R.id.btnInput);
        if (btnInput != null) {
            btnInput.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleKeyboard();
                }
            });
        }
        tvFps = (TextView) findViewById(R.id.fps);

        beebView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                if (onMouseSomething(v, event)) return true;
                return false;
            }
        });

        beebView.setOnTouchListener(new View.OnTouchListener() {
            // Can't use onClickListener: it stops enter and space working.
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (onMouseSomething(v, event)) return true;

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    bbcKeyEvent(BBCKEY_SPACE + BBCKEY_CTRL_MOD, 0, 1);
                    hideKeyboard(v);
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    bbcKeyEvent(BBCKEY_SPACE + BBCKEY_CTRL_MOD, 0, 0);
                    hintActioned("hint_space_to_start");
                }
                return true;
            }
        });

        UserPrefs.setGrandfatheredIn(this, true);
    }

    private void hideKeyboard(View v) {
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.RESULT_UNCHANGED_SHOWN);
        }
    }

    void enrichen(int resourceID) {
        View v = findViewById(resourceID);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            String s = tv.getText().toString();
            if (s.charAt(0) == '<') {
                SpannableStringBuilder b = new SpannableStringBuilder();
                int i = 0;
                while (i < s.length()) {
                    int r = nextEnd(s, '>', i);
                    int l = nextEnd(s, '<', i);
                    int end = Math.min(r, l);
                    int lr = "<>".indexOf(s.charAt(i));
                    if (lr == -1) {
                        b.append(s, i + 1, s.length());
                        break;
                    } else {
                        SpannableString span = new SpannableString(s.substring(i + 1, end) + "\n");
                        span.setSpan(
                                new AlignmentSpan.Standard(lr > 0 ? ALIGN_OPPOSITE : ALIGN_NORMAL),
                                0, span.length(),
                                SPAN_EXCLUSIVE_EXCLUSIVE);
                        b.append(span);
                        i = end;
                    }
                }
                tv.setText(b);
            }
        }
    }

    private int nextEnd(String s, char ch, int i) {
        int a = s.indexOf(ch, i + 1);
        if (a == -1) a = s.length();
        return a;
    }

    private boolean onMouseSomething(final View v, final MotionEvent event) {
        if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {

            long ms = event.getEventTime();
            if (event.getAction() == MotionEvent.ACTION_DOWN) updown = 1;
            if (event.getAction() == MotionEvent.ACTION_UP) updown = 0;
            Log.i(TAG, "onMouseSomething: " + event.getAction());
            if (last_ms + 20 < ms || event.getAction() == MotionEvent.ACTION_UP) {
                last_ms = ms;
                adctick ^= 1;
//						Log.e(TAG, "Hover " + (event.getX() + v.getX()) + ", " + (event.getY() + v.getY()));
                float xx = 1280 * event.getX() / v.getWidth();
                float yy = 1024 - 1024 * event.getY() / v.getHeight();
                if (yy < 0) yy = 0;
                bbcPushAdc(
                        (int) xx * 2 + adctick,
                        (int) yy * 2 + adctick,
                        (event.getButtonState() + updown) * 2 + adctick,
                        updown * Math.round(event.getPressure() * 1024) * 2 + adctick);
            }
            return true;
        }
        return false;
    }

    private void toggleKeyboard() {
        switch (keyboardShowing) {
            case SCREEN_KEYBOARD:
                showKeyboard(KeyboardState.CONTROLLER);
                break;
            case CONTROLLER:
                showKeyboard(KeyboardState.BLUETOOTH_KBD);
                break;
            case BLUETOOTH_KBD:
                showKeyboard(KeyboardState.RS423_CONSOLE);
                break;
            case RS423_CONSOLE:
            default:
                showKeyboard(KeyboardState.SCREEN_KEYBOARD);
                break;
        }
        hintActioned("hint_switch_keyboards");
    }

    private void processDiskViaIntent() {
        // Diskette passed from another process
        Intent intent = getIntent();
        if (intent.getAction() != null &&
                intent.getAction().equals("com.littlefluffytoys.beebdroid.OPEN_DISK")) {
            bbcBreak(0);
            byte[] diskBytes = intent.getExtras().getByteArray("disk_image");
            diskImage = ByteBuffer.allocateDirect(diskBytes.length);
            diskImage.put(diskBytes);
            bbcLoadDisc(diskImage, 1);
        }

        // Disk image opened via intent (e.g. browser download)
        Uri dataUri = intent.getData();
        if (dataUri != null) {
            Toast.makeText(this, "Got data! " + dataUri.toString(), Toast.LENGTH_LONG).show();
            if (dataUri.getScheme().equals("file")) {
                try {
                    InputStream input = new FileInputStream(dataUri.getPath());
                    if (dataUri.getLastPathSegment().endsWith(".zip")) {
                        ZipInputStream in = new ZipInputStream(input);
                        ZipEntry entry = in.getNextEntry();
                        Log.d(TAG, "its a zip! " + entry.getName());
                        input = in;
                    }
                    byte[] diskBytes = readInputStream(input);
                    diskImage = ByteBuffer.allocateDirect(diskBytes.length);
                    diskImage.put(diskBytes);
                    Log.d(TAG, "fileContents " + diskBytes.length);
                    bbcLoadDisc(diskImage, 1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private static byte[] readInputStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(4096);

        // Do the first byte via a blocking read
        outputStream.write(inputStream.read());

        // Slurp the rest
        int available = 0;//inputStream.available();
        boolean run = true;
        while (run && (available = inputStream.available()) > 0) {
            //Log.d(TAG, "slurp " + available);
            while (available > 0) {
                int cbToRead = Math.min(buffer.length, available);
                int cbRead = inputStream.read(buffer, 0, cbToRead);
                if (cbRead <= 0) {
                    run = false;
                    break;
                }
                outputStream.write(buffer, 0, cbRead);
                available -= cbRead;
            }
        }
        return outputStream.toByteArray();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return this;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (keyboardShowing == KeyboardState.BLUETOOTH_KBD)
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    beebView.requestFocus();
                }
            }, 200);
        handler.postDelayed(runInt50, 20);
        handler.postDelayed(runRS423, 20);
        //showHint("hint_load_disks", R.string.hint_load_disks, 5);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(runInt50);
        handler.removeCallbacks(runRS423);
        cancelPendingHints();
    }

    @Override
    public void onStop() {
        super.onStop();
        bbcExit();
        audio.stop();
        playing = false;
//    	System.exit(0); // until native lib is stable
    }

    ByteBuffer diskImage;

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        int index = -1;
        String file = "";
        if (data != null) {
            index = data.getIntExtra("index", -1);
            file = data.getStringExtra("file");
        }
        switch (resultCode) {
            case LoadDisk.ID_RESULT_LOADDISK:
                loadDisk(LoadDisk.selectedDisk, true);
                if (!showHint("hint_switch_keyboards", R.string.hint_switch_keyboards, 5)) {
                    showHint("hint_space_to_start", R.string.hint_space_to_start, 10);
                }
                break;

            case LoadDisk.ID_RESULT_SAVE:
                SavedGameInfo info;
                if (index == -1) {
                    info = new SavedGameInfo();
                } else {
                    info = SavedGameInfo.savedGames.remove(index);
                }
                SavedGameInfo.savedGames.add(0, info);
                info.save(this);
                break;

            case LoadDisk.ID_RESULT_RESTORE:
                info = SavedGameInfo.savedGames.get(index);
                try {
                    if (info.diskInfo != null)
                        loadDisk(info.diskInfo, false);
                    byte[] buffer = new byte[65 * 1024];
                    FileInputStream fileIn = openFileInput(info.filename);
                    DataInputStream din = new DataInputStream(fileIn);
                    din.skip(info.offsetToMachineData);
                    int cbMachine = din.readInt();
                    int cb = fileIn.read(buffer);
                    bbcDeserialize(buffer);
                    din.close();
                    Toast.makeText(this, "Restored", Toast.LENGTH_SHORT).show();
                } catch (IOException ex) {
                    Log.d(TAG, "Error restoring state: " + ex);
                }
                SavedGameInfo.current = info;
                break;

            case LoadDisk.ID_RS423_INJECT:
                injectStream = null ;// openFileInput()
                break;
        }

    }

    private void loadDisk(DiskInfo diskInfo, boolean bootIt) {
        this.diskInfo = diskInfo;

        diskImage = loadFile(diskInfo.getFile());


        // Load the disc and do the disc-start stuff
        if (!TextUtils.isEmpty(diskInfo.bootCmd)) {
            if (bootIt) {
                bbcBreak(0);
            }
            bbcLoadDisc(diskImage, 0);
            keyboardTextWait = 20;
            doFakeKeys(diskInfo.bootCmd);
        } else {
            bbcLoadDisc(diskImage, (bootIt && TextUtils.isEmpty(diskInfo.bootCmd)) ? 1 : 0);
        }

        // Set the right controller for the disk
        ControllerInfo controllerInfo = Controllers.controllersForKnownDisks.get(diskInfo.key);
        if (controllerInfo == null) {
            controllerInfo = Controllers.DEFAULT_CONTROLLER;
        }
        setController(controllerInfo);

        // Show the controller overlay rather than the keyboard
        showKeyboard(KeyboardState.SCREEN_KEYBOARD);

    }

    private void setController(ControllerInfo controllerInfo) {
        currentController = controllerInfo;
        controller.setController(controllerInfo);
        setTriggers(controllerInfo);
    }

    public void showKeyboard(KeyboardState keyboardState) {
        Utils.setVisible(this, R.id.keyboard, keyboardState == KeyboardState.SCREEN_KEYBOARD);
        Utils.setVisible(this, R.id.keyboard_help, keyboardState != KeyboardState.BLUETOOTH_KBD);
        Utils.setVisible(this, R.id.controller, keyboardState == KeyboardState.CONTROLLER);
        Utils.setVisible(this, R.id.kb_bt_alt, keyboardState == KeyboardState.BLUETOOTH_KBD);
        Utils.setVisible(this, R.id.rs423layout, keyboardState == KeyboardState.RS423_CONSOLE);
        final ImageView btnInput = (ImageView) findViewById(R.id.btnInput);
        if (btnInput != null) {
            btnInput.setImageResource(kbdImageForState(keyboardState));
        }
        keyboardShowing = keyboardState;
    }

    private int kbdImageForState(KeyboardState keyboardState) {
        switch (keyboardState) {
            case SCREEN_KEYBOARD:
                return isXperiaPlay ? R.drawable.keyboard_cancel : R.drawable.controller;
            case CONTROLLER:
                return R.drawable.btkbabc;
            case BLUETOOTH_KBD:
                return R.drawable.rs423;
            case RS423_CONSOLE:
            default:
                return R.drawable.bbckeyboard;
        }
    }

    boolean shiftDown;


    int lookup(int keycode, KeyEvent event) {
        // TODO: Somehow fold most of this into Keyboard.unicodeMap
        if (event.isAltPressed()) {
            int beebKey = Keyboard.unicodeAltToBeebKey(event.getKeyCode(), event.isShiftPressed());
            if (beebKey != 0) return beebKey;
        }
        switch (keycode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return BBCKEY_CTRL;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return BBCKEY_SHIFT_RAW;
            case KeyEvent.KEYCODE_ESCAPE:
                return BBCKEY_ESCAPE;
            case KeyEvent.KEYCODE_0:
                return BBCKEY_0; // 0x126 ) :0x27 0;
            case KeyEvent.KEYCODE_1:
                return BBCKEY_1; // 0x30;
            case KeyEvent.KEYCODE_2:
                return BBCKEY_2; // 0x31;
            case KeyEvent.KEYCODE_3:
                return BBCKEY_3; // 0x11;
            case KeyEvent.KEYCODE_4:
                return BBCKEY_4; // 0x12;
            case KeyEvent.KEYCODE_5:
                return BBCKEY_5; // 0x13;
            case KeyEvent.KEYCODE_6:
                return BBCKEY_6; // 0x34;
            case KeyEvent.KEYCODE_7:
                return BBCKEY_7; // 0x134 & :0x24 7;
            case KeyEvent.KEYCODE_8:
                return BBCKEY_8; // 0x15;
            case KeyEvent.KEYCODE_9:
                return BBCKEY_9; // 0x115 ( :0x26 8;
            case KeyEvent.KEYCODE_A:
                return BBCKEY_A; // 0x41;
            case KeyEvent.KEYCODE_B:
                return BBCKEY_B; // 0x64;
            case KeyEvent.KEYCODE_C:
                return BBCKEY_C; // 0x52;
            case KeyEvent.KEYCODE_D:
                return BBCKEY_D; // 0x32;
            case KeyEvent.KEYCODE_E:
                return BBCKEY_E; // 0x22;
            case KeyEvent.KEYCODE_F:
                return BBCKEY_F; // 0x43;
            case KeyEvent.KEYCODE_G:
                return BBCKEY_G; // 0x53;
            case KeyEvent.KEYCODE_H:
                return BBCKEY_H; // 0x54;
            case KeyEvent.KEYCODE_I:
                return BBCKEY_I; // 0x25;
            case KeyEvent.KEYCODE_J:
                return BBCKEY_J; // 0x45;
            case KeyEvent.KEYCODE_K:
                return BBCKEY_K; // 0x46;
            case KeyEvent.KEYCODE_L:
                return BBCKEY_L; // 0x56;
            case KeyEvent.KEYCODE_M:
                return BBCKEY_M; // 0x65;
            case KeyEvent.KEYCODE_N:
                return BBCKEY_N; // 0x55;
            case KeyEvent.KEYCODE_O:
                return BBCKEY_O; // 0x36;
            case KeyEvent.KEYCODE_P:
                return BBCKEY_P; // 0x37;
            case KeyEvent.KEYCODE_Q:
                return BBCKEY_Q; // 0x10;
            case KeyEvent.KEYCODE_R:
                return BBCKEY_R; // 0x33;
            case KeyEvent.KEYCODE_S:
                return BBCKEY_S; // 0x51;
            case KeyEvent.KEYCODE_T:
                return BBCKEY_T; // 0x23;
            case KeyEvent.KEYCODE_U:
                return BBCKEY_U; // 0x35;
            case KeyEvent.KEYCODE_V:
                return BBCKEY_V; // 0x63;
            case KeyEvent.KEYCODE_W:
                return BBCKEY_W; // 0x21;
            case KeyEvent.KEYCODE_X:
                return BBCKEY_X; // 0x42;
            case KeyEvent.KEYCODE_Y:
                return BBCKEY_Y; // 0x44;
            case KeyEvent.KEYCODE_Z:
                return BBCKEY_Z; // 0x61;
            case KeyEvent.KEYCODE_SPACE:
                return BBCKEY_SPACE; // 0x62;
            case KeyEvent.KEYCODE_ENTER:
                return BBCKEY_ENTER; //  0x49;
            case KeyEvent.KEYCODE_DEL:
                return BBCKEY_DELETE; //  0x59;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return BBCKEY_COLON; // 0x31 2 :0x124 ' ;
            case KeyEvent.KEYCODE_POUND:
                return BBCKEY_3; // 0x111; // '#' is Shift+3
            case KeyEvent.KEYCODE_MINUS:
                return BBCKEY_MINUS; // 0x238: 0x17;
            case KeyEvent.KEYCODE_EQUALS:
                return BBCKEY_MINUS; // BBCKEY_EQUALS; // 0x117 = shift+[
            case KeyEvent.KEYCODE_AT:
                return BBCKEY_AT; // 0x47;
            case KeyEvent.KEYCODE_STAR:
                return BBCKEY_COLON; // 0x148;
            case KeyEvent.KEYCODE_PERIOD:
                return BBCKEY_PERIOD; // 0x248:0x67;
            case KeyEvent.KEYCODE_SEMICOLON:
                return BBCKEY_SEMICOLON; // 0x57;
            case KeyEvent.KEYCODE_SLASH:
                return BBCKEY_SLASH; // 0x68;
            case KeyEvent.KEYCODE_PLUS:
                return BBCKEY_SEMICOLON; // 0x157;
            case KeyEvent.KEYCODE_COMMA:
                return BBCKEY_COMMA; // 0x66;
            case KeyEvent.KEYCODE_GRAVE:
                return BBCKEY_CARET; // 0x??;
            case KeyEvent.KEYCODE_BACKSLASH:
                return BBCKEY_BACKSLASH;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return BBCKEY_BRACKET_LEFT_SQ; // 0x??;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return BBCKEY_BRACKET_RIGHT_SQ; // 0x??;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return BBCKEY_CAPS;
            case KeyEvent.KEYCODE_F10:
                return BBCKEY_SHIFTLOCK;
            case KeyEvent.KEYCODE_F11:
                return BBCKEY_AT;
            case KeyEvent.KEYCODE_F12:
                return BBCKEY_UNDERSCORE;
            case KeyEvent.KEYCODE_DPAD_UP:
                return BBCKEY_ARROW_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return BBCKEY_ARROW_DOWN;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return BBCKEY_ARROW_RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return BBCKEY_ARROW_LEFT;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return BBCKEY_COPY;
        }
        return BBCKEY_BREAK; // !?!? 0xaa;
    }

    //0x218=up arrow  0x118=%
    //0x278= 1/2      0x178=dbl vert pipe


    public static float DPI_MULT = 1;
    public static float DP_SCREEN_WIDTH = 320;

    public static float dp(float d) {
        return (d * DPI_MULT + 0.5f);
    }

    public static boolean useDpad = false;

    public static class BeebView extends SurfaceView implements SurfaceHolder.Callback, KeyEvent.Callback {

        // Emulated display
        static final int W = 672;
        static final int H = 272 * 2;
        static final float ASPECT = ((float) H / (float) W);

        EGL10 egl;
        int textureId; //Bitmap screen;

        int screenwidth;
        int screenheight;

        private Rect rcSrc;
        private Rect rcDst;
        //private Paint paint;
        private GL10 gl;
        private EGLConfig config;
        private EGLContext ctxt;
        private EGLDisplay display;
        private EGLSurface surface;
        int width, height;

        public BeebView(Context context, AttributeSet attrs) {
            super(context, attrs);
            getHolder().addCallback(this);
            //screen = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
            rcSrc = new Rect(0, 0, W, H / 2);

        }

        @Override
        public boolean performClick() {
            return true; // handled.
        }

        private void cleanupgl() {
            // Unbind and destroy the old EGL surface, if there is one.
            if (surface != null) {
                egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl.eglDestroySurface(display, surface);
                surface = null;
            }

        }

        public void initgl() {
            cleanupgl();

            // Create new surface. Must succeed.
            surface = egl.eglCreateWindowSurface(display, config, getHolder(), null);
            if (null == surface) {
                throw new RuntimeException("eglCreateWindowSurface");
            }

            // Bind the rendering context to the surface.
            if (!egl.eglMakeCurrent(display, surface, surface, ctxt)) {
                throw new RuntimeException("eglMakeCurrent");
            }
            gl = (GL10) ctxt.getGL();

        }

        @Override
        public void onMeasure(int ws, int hs) {
            super.onMeasure(ws, hs);
            int w = getMeasuredWidth();
            int h = getMeasuredHeight();
            rcDst = new Rect(0, 0, w, h);//(int)(w * ASPECT));
            Log.d(TAG, "beebView is " + rcDst.width() + "x" + rcDst.height());
        }

        private static int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated");
            // Lots of stunningly tedious EGL setup. All we want is a 565 surface.
            egl = (EGL10) EGLContext.getEGL();
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!egl.eglInitialize(display, version)) {
                throw new RuntimeException("eglInitialize failed");
            }

            int[] attrib_list = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};

            config = getEglConfig565(egl, display);

            ctxt = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attrib_list);


        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "surfaceChanged");
            this.width = width;
            this.height = height;
            gl = null;
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "surfaceDestroyed");
            cleanupgl();
            egl = null;
        }
	    
	   /* public void blit(int wh) {
			screenwidth = wh & 0xffff;
			if (screenwidth<=320) screenwidth*=2;
			screenheight = wh>>16;
			
			// Try to fill the display properly
	    	//rcSrc.set(0,0,W, H);
	    	//int adj = (W - screenwidth)/2;
	    	//rcSrc.inset(adj, 0);
	    	//int w = getMeasuredWidth();
	    	//float fh = w * ASPECT;
	    	//fh *= 1.2f + (adj/(float)W);
	    	//rcDst.set(0,0, w, (int)fh);
	    	
	    	Canvas canvas = getHolder().lockCanvas();
	        canvas.drawBitmap(screen, rcSrc, rcDst, paint);
	        getHolder().unlockCanvasAndPost(canvas);
	    }*/


    }

    public void videoCallback() {
        fps++;

        if (use25fps && (1 == (fps & 1))) {

        } else {

            // Swap buffers!
            if (beebView.egl != null) {
                beebView.egl.eglSwapBuffers(beebView.display, beebView.surface);
            }
        }

        // Update status text once per second
        if (System.currentTimeMillis() - time_fps >= 1000) {
            if (tvFps != null) {
                tvFps.setText("FPS: " + fps);
            }
            fps = 0;
            skipped = 0;
            time_fps = System.currentTimeMillis();
        }

    }

    public ByteBuffer loadFile(File file) {
        InputStream strm;
        ByteBuffer buff = null;
        try {
            strm = new FileInputStream(file);
            int size = strm.available();
            //Log.d("Beebdroid", "loadAsset " + assetPath + " is " + size + " bytes");
            buff = ByteBuffer.allocateDirect(size);
            byte[] localbuff = new byte[size];
            strm.read(localbuff, 0, size);
            strm.close();
            buff.put(localbuff);
            buff.position(0);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return buff;
    }


    private static final int ID_SWITCHKEYBOARD = 1;
    private static final int ID_LOADDISK = 2;
    //private static final int ID_CONTROLLERS = 2;
    private static final int ID_ABOUT = 3;
    private static final int ID_SAVEDGAMES = 4;
    private static final int ID_SAVEMACHINE = 5;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, ID_SWITCHKEYBOARD, 0, getString(R.string.button_switch_keyboard));
        menu.add(0, ID_LOADDISK, 1, getString(R.string.button_load_disk));
        menu.add(0, ID_SAVEMACHINE, 2, getString(R.string.button_save_machine));
        menu.add(0, ID_ABOUT, 3, getString(R.string.button_about));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case ID_SWITCHKEYBOARD:
                toggleKeyboard();
                return true;
            case ID_LOADDISK:
                onOpenClicked(null);
                return true;
            case ID_SAVEMACHINE:
                onOpenClicked(null);
                return true;
            case ID_ABOUT:
                startActivity(new Intent(Beebdroid.this, AboutActivity.class));
                return true;
        }

        return false;
    }

    boolean playing;
    int heh;


    long lastAudioCallbackTime;

    public void audioCallback(int pos, int cb) {
		/*String s = "";
		for (int i=0 ; i<16 ; i++) {
			s += Integer.toHexString(audiobuff[i]);
			s += " ";
		}
		Log.d(TAG, "audiocb " + s);*/
        byte[] raw = audiobuff.array();
        audio.write(raw, pos, cb);
        //audio.flush();


        if (!playing) {
            audio.play();
            playing = true;
        }
    }

    FileInputStream injectStream = null;
    File injectFile = null;
    FileOutputStream captureStream = null;

    public void setRs423Capture(View v) {
        if (captureStream != null) {
            // We're capturing. Stop it.
            try {
                captureStream.close();
                rs423printer.setText("Closed capture file " + captureFile.getAbsolutePath() + "\n");
            } catch (IOException e) {
                rs423printer.setText("Failed to close capture file: " + e +"\n");
            }
            captureStream = null;
            captureFile = null;
            return;
        }
        File dir = getExternalFilesDir("rs423captures");
        captureFile = new File(dir, new Date().toString());
        rs423printer.setText("Capturing to " + captureFile.getAbsolutePath() + "\n");
        try {
            captureStream = new FileOutputStream(captureFile.getAbsolutePath());
        } catch (FileNotFoundException e) {
            rs423printer.setText("Failed to create capture file: " + e + "\n");
        }
    }

    public void setRs423Inject(View v) {
        if (injectStream != null) {
            Intent fileintent = new Intent(Intent.ACTION_GET_CONTENT);
            fileintent.setType("gagt/sdf");
            try {
                startActivityForResult(fileintent, LoadDisk.ID_RS423_INJECT);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No activity can handle picking a file!?", Toast.LENGTH_LONG).show();
            }
        }
    }

    public void setRs423Io(View v) {
        doFakeKeys("\r*fx5 2\rvdu2\r*fx2 1\r");
    }

    public void unsetRs423Io(View v) {
        rs423keyboard.setText("\rVDU3\r*fx 2 0\rVDU3\r");
    }

    public void onOpenClicked(View v) {
        Intent intent = new Intent(this, LoadDisk.class);
        intent.putExtra("startTab", 0);
        startActivityForResult(intent, ID_LOADDISK);
        //startDisksActivity((diskInfo == null) ? 0 : 2); // i.e. start on 'Installed' tab if no disk loaded, 'Saved' tab if a disk *is* loaded
    }

    /*
     * OpenGL help
     */
    public static EGLConfig getEglConfig565(EGL10 egl, EGLDisplay display) {
        int[] version = new int[2];
        egl.eglInitialize(display, version);

        EGLConfig[] conf = new EGLConfig[100];
        int[] num_conf = new int[100];
        egl.eglGetConfigs(display, conf, 100, num_conf);

        int[] red = new int[1];
        int[] blue = new int[1];
        int[] green = new int[1];
        int[] alpha = new int[1];
        for (int i = 0; i < 100; i++) {
            if (conf[i] == null)
                break;
            egl.eglGetConfigAttrib(display, conf[i], EGL10.EGL_RED_SIZE, red);
            egl.eglGetConfigAttrib(display, conf[i], EGL10.EGL_BLUE_SIZE, blue);
            egl.eglGetConfigAttrib(display, conf[i], EGL10.EGL_GREEN_SIZE, green);
            egl.eglGetConfigAttrib(display, conf[i], EGL10.EGL_ALPHA_SIZE, alpha);
            android.util.Log.d("Beebdroid", "conf: R:" + red[0] + " G:" + green[0] + " B:" + blue[0] + " A:" + alpha[0]);
            if (red[0] == 5 && green[0] == 6 && blue[0] == 5) {
                return conf[i];
            }
        }
        return null;
    }


    String hintID;
    int hintResourceID;
    final Runnable showHint = new Runnable() {

        @Override
        public void run() {
            //incrementHintActionCount(hintID);
            Toast.makeText(Beebdroid.this, getString(hintResourceID), Toast.LENGTH_LONG).show();
        }
    };

    private void cancelPendingHints() {
        handler.removeCallbacks(showHint);
    }

    private void hintActioned(String hintID) {
        cancelPendingHints();
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).edit().putBoolean(hintID, true).commit();
    }

    /**
     * @param hintID         the identifier we store in shared preferences to say if it's already happened or not
     * @param hintResourceID the resource ID pointing to the string to display
     * @param secondsDelay   how many seconds until the hint should be shown
     *                       returns true if hint shown, false if it was already actioned
     */
    private boolean showHint(String hintID, int hintResourceID, int secondsDelay) {
        final boolean hintActioned = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean(hintID, false);
        if (!hintActioned) {
            this.hintID = hintID;
            this.hintResourceID = hintResourceID;
            cancelPendingHints();
            handler.postDelayed(showHint, secondsDelay * 1000);
            return true;
        } else {
            return false;
        }
    }


    //
    // TRIGGERS
    //
    public void setTriggers(ControllerInfo controllerInfo) {
        short[] pc_triggers = {};
        if (controllerInfo.triggers != null) {
            pc_triggers = new short[controllerInfo.triggers.size()];
            for (int i = 0; i < controllerInfo.triggers.size(); i++) {
                pc_triggers[i] = controllerInfo.triggers.get(i).pc_trigger;
            }
        }
        bbcSetTriggers(pc_triggers);
        last_trigger = 0;
    }

}