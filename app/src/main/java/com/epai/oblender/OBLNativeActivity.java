package com.epai.oblender;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.FragmentTransaction;
import android.app.NativeActivity;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.Manifest;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.epai.oblfiles.FileUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;

public class OBLNativeActivity extends NativeActivity {
    private static final String TAG = "OBLNativeActivity";

    // Load native libraries
    private static boolean sNativeLibLoaded = false;
    static {
        try {
            System.loadLibrary("native-lib");
            System.loadLibrary("c++_shared");
            System.loadLibrary("blender");
            sNativeLibLoaded = true;
            Log.d(TAG, "Native libraries loaded successfully");
        } catch (UnsatisfiedLinkError | SecurityException e) {
            sNativeLibLoaded = false;
            Log.w(TAG, "Native libraries not available (pure Java mode): " + e.getMessage());
        }
    }

    private OblSettingFragment mOblSettingFragment;
    private boolean mBooleanLastOblSettingFragmentVisible;
    private EditText mEditText;
    private WindowManager mWindowManager;
    private FrameLayout mFrameLayout;

    public native void initial(String homePath, String configPath);
    public native void setNativeWindow(Surface surface);
    public native void setScreenSize(int width, int height);
    public native void onPauseNative();
    public native void onResumeNative();
    public native void onSurfaceDestroyed();

    private WindowWindow mWindowWindow;
    private SurfaceView mSurfaceView;
    private boolean mBooleanSurfaceViewIsShow;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 修复：super.onCreate() 必须最先调用
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            hideToolbar();
            initialEditText();

            AssetManager assetManager = getAssets();

            // 修复：getExternalFilesDir() 可能返回 null，增加安全保护
            File externalFilesDir = getExternalFilesDir("obl");
            String externFileDirPath = ".";
            if (externalFilesDir != null) {
                externFileDirPath = externalFilesDir.getPath();
            } else {
                Log.w(TAG, "getExternalFilesDir returned null, using fallback path");
                // Fallback to internal files dir
                externFileDirPath = getFilesDir().getPath();
            }
            // 修复：FileUtils.getExternStorageDir() 需要两个参数
            String homePath = FileUtils.getExternStorageDir(this, "obl");
            if (homePath == null) {
                Log.e(TAG, "Cannot get external storage directory");
                Toast.makeText(this, "存储权限不足，请手动授予", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            if (sNativeLibLoaded) {
                try {
                    initial(homePath, externFileDirPath + "/config");
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "initial() not available in native library: " + e.getMessage());
                }
            } else {
                Log.w(TAG, "Skipping native initial() - native libraries not loaded");
            }

            // 修复：检查权限状态后再设置视图
            if (checkPerssion(Manifest.permission.CAMERA, 0)) {
                mSurfaceView = new SurfaceView(this);
                SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                surfaceHolder.setFormat(PixelFormat.RGBA_8888);
                surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder surfaceHolder) {
                        if (sNativeLibLoaded) {
                            try {
                                setNativeWindow(surfaceHolder.getSurface());
                            } catch (UnsatisfiedLinkError e) {
                                Log.w(TAG, "setNativeWindow() not available: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                        if (sNativeLibLoaded) {
                            try {
                                setScreenSize(i1, i2);
                            } catch (UnsatisfiedLinkError e) {
                                Log.w(TAG, "setScreenSize() not available: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                        if (sNativeLibLoaded) {
                            try {
                                onSurfaceDestroyed();
                            } catch (UnsatisfiedLinkError e) {
                                Log.w(TAG, "onSurfaceDestroyed() not available: " + e.getMessage());
                            }
                        }
                    }
                });
            } else {
                Log.d(TAG, "Camera permission not granted, SurfaceView not created");
            }
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        if (sNativeLibLoaded) {
            try {
                onPauseNative();
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "onPauseNative() not available: " + e.getMessage());
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sNativeLibLoaded) {
            try {
                onResumeNative();
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "onResumeNative() not available: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (sNativeLibLoaded) {
            try {
                onSurfaceDestroyed();
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "onSurfaceDestroyed() not available: " + e.getMessage());
            }
        }
        super.onDestroy();
    }

    public boolean checkPerssion(String permission, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
                return false;
            }
        }
        return true;
    }

    private void hideToolbar() {
        // Placeholder for toolbar hiding logic
    }

    private void initialEditText() {
        // Placeholder for EditText initialization
    }
}
