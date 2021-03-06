package com.littlefluffytoys.beebdroid;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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

import android.app.Activity;
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
import android.text.TextUtils;
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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Rect;

import static com.littlefluffytoys.beebdroid.BeebKeys.*;
import static com.littlefluffytoys.beebdroid.Keyboard.unicodeToBeebKey;


public class Beebdroid extends Activity {
    private static final String TAG = "Beebdroid";
    // The emulator keypresses from the physical keyboard are only 1-2ms and
    // don't register, so observe this and delay the key up.
    // Ideally, we'd notice keyrepeat before this time and cancel the scheduled key up.
    public static final int MIN_KEY_DOWNUP_MS = 50; //10;
    public static boolean use25fps = false;

    private enum KeyboardState {SCREEN_KEYBOARD, CONTROLLER, BLUETOOTH_KBD}

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
    List<KeyEvent> keyboardTextEvents = new ArrayList<KeyEvent>();
    KeyCharacterMap map = KeyCharacterMap.load(0);
    int fps, skipped;
    TextView tvFps;
    private KeyboardState keyboardShowing = KeyboardState.SCREEN_KEYBOARD;
    ControllerInfo currentController;
    boolean isXperiaPlay;

    // Load our native library
    static {
        System.loadLibrary("bbcmicro");
    }

    // JNI interface
    public native void bbcInit(ByteBuffer mem, ByteBuffer roms, ByteBuffer audiob, int flags);

    public native void bbcBreak(int flags);

    public native void bbcExit();

    public native int bbcRun();

    public native int bbcInitGl(int width, int height);

    public native void bbcLoadDisc(ByteBuffer disc, int autoboot);

    public native void bbcSetTriggers(short[] pc_triggers);

    public static native void bbcKeyEvent(int scancode, int flags, int down);

    public native int bbcSerialize(byte[] buffer);

    public native void bbcDeserialize(byte[] buffer);

    public native int bbcGetThumbnail(Bitmap bmp);

    public native int bbcGetLocks();

    public native void bbcPushAdc(int x1, int y1, int x2, int y2);

    long time_fps;

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
                    final KeyEvent event = keyboardTextEvents.remove(0);
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        onKeyDown(event.getKeyCode(), event);
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                onKeyUp(event.getKeyCode(), event);
                                keyboardTextWait = 1;
                            }
                        }, MIN_KEY_DOWNUP_MS);
                    }
                }
            }
        }
    };

    final HashMap<Integer, Runnable> delayedUp = new HashMap<Integer, Runnable>();

//    static long thing = 0;

    boolean onKeyUpDown(final int keycode, KeyEvent event, final int isDown) {
//        Log.e(TAG, "updown " + (thing - event.getEventTime()) + " " + event.toString());
//        thing = event.getEventTime();
        if (keyboardShowing != KeyboardState.BLUETOOTH_KBD && isXperiaPlay && onXperiaKey(keycode, event, isDown)) {
            return true;
        }

        if (isDown == 1
                && event.isAltPressed()
                && event.getUnicodeChar(0) == '2') {
            toggleKeyboard();
            if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {
                beebView.requestFocus();
            }
            return true;
        }

        if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {
            if (keycode == KeyEvent.KEYCODE_MENU) return false; // Someone else's problem!
            int scancode = bbcKeyActionFromUnicode(event);
            if (isDown == 1) showInfo(event, scancode);
            if (scancode != 0) return true;
            return false;
        }

        // If pressed 'back' while game loaded, reset the emulator rather than exit the app
        if (keycode == KeyEvent.KEYCODE_BACK && isDown == 1) {
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
            shiftDown = isDown == 1;
        }

        final int scancode = lookup(keycode);
        if (isDown == 1 || event.getDownTime() - event.getEventTime() > MIN_KEY_DOWNUP_MS) {
            unscheduleKeyup(keycode);
            bbcKeyEvent(scancode | BBCKEY_RAW_MOD, shiftDown ? 1 : 0, isDown);
        } else {
            scheduleKeyup(keycode, new Runnable() {
                @Override
                public void run() {
                    synchronized (delayedUp) {
                        if (delayedUp.remove(keycode) == this)
                            bbcKeyEvent(scancode | BBCKEY_RAW_MOD, shiftDown ? 1 : 0, isDown);
                    }
                }
            });
        }
        if (isDown == 1) showInfo(event, scancode);
        return false;
    }

    private void showInfo(KeyEvent event, int scancode) {
        int u0 = event.getUnicodeChar();
        int u1 = event.getUnicodeChar(KeyEvent.META_SHIFT_ON);
        TextView t = (TextView) this.findViewById(R.id.info);
        String text = uToStr(u0) + "=" + u0 +
                " " + uToStr(u1) + "=" + u1 +
                " " + Integer.toHexString(scancode) + "=inkey(-" + (0xff & scancode + 1) + ")" +
                " " + KeyEvent.keyCodeToString(event.getKeyCode());
        t.setText(text);
    }

    @Override
    public boolean onKeyDown(int keycode, KeyEvent event) {
        return onKeyUpDown(keycode, event, 1) || super.onKeyDown(keycode, event);
    }

    @Override
    public boolean onKeyUp(int keycode, KeyEvent event) {
        return onKeyUpDown(keycode, event, 0) || super.onKeyUp(keycode, event);
    }

    private String uToStr(int u0) {
        try {
            return new String(Character.toChars(u0));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "uToStr(" + u0 + ") threw " + e);
            return "";
        }
    }

    // Keep track of what caused a keydown in case shift shifts ; to : and messes up the key.
    HashMap<Integer, Integer> downKeycode = new HashMap<Integer, Integer>();

    private int bbcKeyActionFromUnicode(final KeyEvent event) {
        final int keycode = event.getKeyCode();
        final boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        if (isDown) {
            unscheduleKeyup(keycode);
            int scancode = unicodeToBeebKey(event);
            if (scancode != 0) {
                downKeycode.put(keycode, scancode);
                bbcKeyEvent(scancode, 0, 1);
            }
            return scancode;
        } else {
            final Integer scancode = downKeycode.remove(keycode);
            if (scancode != null && scancode != 0) {
                if (event.getDownTime() - event.getEventTime() > MIN_KEY_DOWNUP_MS) {
                    // Probably don't care about unscheduling a keyup, but...
                    unscheduleKeyup(keycode);
                    bbcKeyEvent(scancode, 0, 0);
                }
                else {
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
        KeyEvent[] evs = map.getEvents(text.toCharArray());
        keyboardTextEvents.addAll(Arrays.asList(evs));
    }

    int adctick = 0;
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
            for (File file : dir.listFiles()) {
                file.delete();
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

    private boolean onMouseSomething(View v, MotionEvent event) {
        if (keyboardShowing == KeyboardState.BLUETOOTH_KBD) {

            long ms = event.getEventTime();
            if (last_ms + 20 < ms) {
                last_ms = ms;
                adctick ^= 1;
//						Log.e(TAG, "Hover " + (event.getX() + v.getX()) + ", " + (event.getY() + v.getY()));
                float xx = 1280 * event.getX() / v.getWidth();
                float yy = 1024 - 1024 * event.getY() / v.getHeight();
                if (yy < 0) yy = 0;
                bbcPushAdc(
                        (int) xx * 2 + adctick,
                        (int) yy * 2 + adctick,
                        event.getButtonState() * 2 + adctick, 0);
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
        //showHint("hint_load_disks", R.string.hint_load_disks, 5);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(runInt50);
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
        if (data != null) {
            index = data.getIntExtra("index", -1);
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
            default:
                return R.drawable.keyboard;
        }
    }

    boolean shiftDown;


    int lookup(int keycode) {
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