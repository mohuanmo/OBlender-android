package com.epai.oblender;

import android.Manifest;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.epai.oblfiles.InstallOBLFiles;

/**
 * 🔧 修复记录:
 * 1. onActivityResult 中重新检查 checkWriteExternalFilePermission()
 * 2. 权限被拒绝时不崩溃，降级继续
 * 3. 保持 InstallOBLFiles 原始 API 不变
 * 4. 增加 onResume 检查：用户从系统设置直接授权返回时自动继续
 * 5. 使用主线程 Looper 避免 Handler 泄漏风险
 */
public class StartupActivity extends AppCompatActivity {
    private String stringHomePath = "";
    private String stringConfigPath = "";
    private final int mIntRequrestID = 1000;
    private final int mIntRequrestIDInternet = 1001;
    private final int mIntTimerDelay = 1500;
    private boolean mIsRequestingPermission = false;  // 🔧 新增：防止重复请求

    private enum MSG_ID {
        MSG_ID_PERMISSION,
        MSG_ID_INTERNETPERMISSION,
        MSG_ID_COPYFILE,
        MSG_ID_STARTACTIVITY
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenUtils.fullScreen(getWindow());
        setContentView(R.layout.activity_startup);

        // 🔧 修复：使用主线程 Looper
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mHandler.sendEmptyMessage(MSG_ID.MSG_ID_PERMISSION.ordinal());
            }
        }, mIntTimerDelay);
    }

    // 🔧 新增：onResume 检查权限状态（用户从系统设置返回时触发）
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mIsRequestingPermission) {
            if (Environment.isExternalStorageManager()) {
                Log.i("StartupActivity", "MANAGE_EXTERNAL_STORAGE granted (onResume)");
                mIsRequestingPermission = false;
                mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
            }
        }
    }

    // 🔧 修复：使用 MainLooper 避免内存泄漏
    private final Handler mHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what == MSG_ID.MSG_ID_PERMISSION.ordinal()) {
                if (!checkWriteExternalFilePermission()) {
                    showAskExternalFileWritePermissionDlg();
                } else {
                    mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                }
            } else if (msg.what == MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal()) {
                if (ContextCompat.checkSelfPermission(StartupActivity.this,
                        Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(StartupActivity.this,
                            new String[]{Manifest.permission.INTERNET}, mIntRequrestIDInternet);
                } else {
                    mHandler.sendEmptyMessage(MSG_ID.MSG_ID_COPYFILE.ordinal());
                }
            } else if (msg.what == MSG_ID.MSG_ID_COPYFILE.ordinal()) {
                // 保持原始 API 不变
                InstallOBLFiles installOBLFiles = new InstallOBLFiles();
                InstallOBLFiles.OBLFilePath oblFilePath =
                        installOBLFiles.installOBLFiles(StartupActivity.this);
                if (oblFilePath != null) {
                    stringHomePath = oblFilePath.mStringHomePath;
                    stringConfigPath = oblFilePath.mStringConfigPath;
                }
                mHandler.sendEmptyMessage(MSG_ID.MSG_ID_STARTACTIVITY.ordinal());
            } else if (msg.what == MSG_ID.MSG_ID_STARTACTIVITY.ordinal()) {
                Intent intent = new Intent(StartupActivity.this, OBLNativeActivity.class);
                intent.putExtra("HomePath", stringHomePath);
                intent.putExtra("ConfigPath", stringConfigPath);
                startActivity(intent);
                StartupActivity.this.finish();
            }
            return false;
        }
    });

    private boolean checkWriteExternalFilePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void showAskExternalFileWritePermissionDlg() {
        // 🔧 防止多次点击打开多个 Settings 页面
        if (mIsRequestingPermission) return;
        mIsRequestingPermission = true;

        AlertDialog.Builder normalDialog = new AlertDialog.Builder(this);
        normalDialog.setTitle("获取读写文件权限");
        normalDialog.setMessage("需要读写文件权限来复制资源文件");
        normalDialog.setPositiveButton("确定",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" +
                                    StartupActivity.this.getPackageName()));
                            startActivityForResult(intent, mIntRequrestID);
                        } else {
                            ActivityCompat.requestPermissions(StartupActivity.this,
                                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    mIntRequrestID);
                        }
                    }
                });
        normalDialog.setNegativeButton("取消",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mIsRequestingPermission = false;
                        Toast.makeText(StartupActivity.this,
                                "存储权限获取失败, 功能可能受限",
                                Toast.LENGTH_LONG).show();
                        mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                    }
                });
        AlertDialog dlg = normalDialog.create();
        dlg.show();
        Button button = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setTextColor(Color.BLUE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mIntRequrestID == requestCode) {
            mIsRequestingPermission = false;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (checkWriteExternalFilePermission()) {
                        mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                    } else {
                        Toast.makeText(StartupActivity.this,
                                "存储权限未授予, 将使用 App 内部存储",
                                Toast.LENGTH_LONG).show();
                        mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                    }
                }
            }, mIntTimerDelay);
        } else if (mIntRequrestIDInternet == requestCode) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mHandler.sendEmptyMessage(MSG_ID.MSG_ID_COPYFILE.ordinal());
                }
            }, mIntTimerDelay);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mIntRequrestID == requestCode) {
            mIsRequestingPermission = false;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (checkWriteExternalFilePermission()) {
                        Log.i("StartupActivity", "MANAGE_EXTERNAL_STORAGE granted");
                        mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                    } else {
                        Log.w("StartupActivity", "MANAGE_EXTERNAL_STORAGE not granted");
                        Toast.makeText(StartupActivity.this,
                                "未授予所有文件访问权限, 使用 App 内部存储",
                                Toast.LENGTH_LONG).show();
                        mHandler.sendEmptyMessage(MSG_ID.MSG_ID_INTERNETPERMISSION.ordinal());
                    }
                }
            }, mIntTimerDelay);
        }
    }

    // 保留原 exitApp 方法
    private void exitApp() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
        super.onBackPressed();
    }
}
