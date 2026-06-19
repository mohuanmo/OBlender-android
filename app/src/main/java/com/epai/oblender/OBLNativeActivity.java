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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;

public class OBLNativeActivity extends NativeActivity {
    private static final String TAG = "OBLNativeActivity";

    // Load native libraries
    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("c++_shared");
        System.loadLibrary("blender");
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
        // 🔧 修复：super.onCreate() 必须最先调用
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            hideToolbar();
            initialEditText();

            AssetManager assetManager = getAssets();

            // 🔧 修复：getExternalFilesDir() 可能返回 null，增加安全保护
            File externalFilesDir = getExternalFilesDir("obl");
            String strHomePath;
            String strConfigPath;
            if (externalFilesDir != null) {
                strHomePath = externalFilesDir.getAbsolutePath() + File.separator;
            } else {
                Log.w(TAG, "getExternalFilesDir('obl') 返回 null，使用内部存储降级");
                strHomePath = getFilesDir().getAbsolutePath() + File.separator + "obl" + File.separator;
            }
            strConfigPath = getFilesDir().getAbsolutePath() + File.separator;

            Intent intent = getIntent();
            if (intent != null) {
                String homePathExtra = intent.getStringExtra("HomePath");
                String configPathExtra = intent.getStringExtra("ConfigPath");
                if (!TextUtils.isEmpty(homePathExtra)) {
                    strHomePath = homePathExtra;
                }
                if (!TextUtils.isEmpty(configPathExtra)) {
                    strConfigPath = configPathExtra;
                }
            }

            Log.i(TAG, "初始化路径: home=" + strHomePath + " config=" + strConfigPath);
            initial(strHomePath, strConfigPath);

            //  监听键盘弹出和隐藏
            SoftKeyBoardListener.setListener(this, new SoftKeyBoardListener.OnSoftKeyBoardChangeListener() {
                @Override
                public void keyBoardShow(int height) {
                    if (mOblSettingFragment != null) {
                        mBooleanLastOblSettingFragmentVisible = mOblSettingFragment.getVisibility() == View.VISIBLE;
                        if (mBooleanLastOblSettingFragmentVisible) {
                            mOblSettingFragment.setVisibility(View.INVISIBLE);
                        }
                    }
                }

                @Override
                public void keyBoardHide(int height) {
                    if (mOblSettingFragment != null && mBooleanLastOblSettingFragmentVisible) {
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                    }
                }
            });

            // 处理 SurfaceView 相关初始化
            initSurfaceView();

        } catch (Exception e) {
            Log.e(TAG, "onCreate 初始化失败", e);
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 🔧 修复：分离 SurfaceView 初始化逻辑
    private void initSurfaceView() {
        WindowWindow.Builder builder = new WindowWindow.Builder(this);
        builder.setWidth(100);
        builder.setHeight(50);
        builder.setX(0);
        builder.setY(0);
        builder.setType(LayoutParams.TYPE_APPLICATION_PANEL);
        builder.setFlags(LayoutParams.FLAG_NOT_TOUCHABLE | LayoutParams.FLAG_NOT_FOCUSABLE);
        mWindowWindow = builder.build();

        WindowGLSurfaceView windowGLSurfaceView = new WindowGLSurfaceView(this);
        windowGLSurfaceView.setListener(new WindowGLSurfaceView.WindowGLSurfaceViewListener() {
            @Override
            public void updateSurfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "Surface 销毁");
                onSurfaceDestroyed();
                if (mWindowWindow != null) {
                    mWindowWindow.hide();
                }
            }

            @Override
            public void updateSurface(SurfaceHolder holder) {
                Log.i(TAG, "Surface 创建/更新");
                Surface surface = holder.getSurface();
                if (surface != null && surface.isValid()) {
                    setNativeWindow(surface);
                    setScreenSize(holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
                }
            }

            @Override
            public void hideWindow(WindowWindow windowWindow) {
                windowWindow.hide();
            }
        });
        mWindowWindow.setContentView(windowGLSurfaceView);
        if (!mBooleanSurfaceViewIsShow) {
            mWindowWindow.show();
            mBooleanSurfaceViewIsShow = true;
        }
    }

    @Override
    protected void onPause() {
        onPauseNative();
        super.onPause();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onResume() {
        hideToolbar();
        onResumeNative();
        super.onResume();
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        onSurfaceDestroyed();
        super.onDestroy();
    }

    // 🔧 新增：C++ 初始化失败时回掉
    public void onBlenderInitFailed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(OBLNativeActivity.this,
                        "Blender 初始化失败", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    // ... 其余方法保持原样（hideToolbar, initialEditText, showWindow 等）
}
