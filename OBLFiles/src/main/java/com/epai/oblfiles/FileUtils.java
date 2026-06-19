package com.epai.oblfiles;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static android.os.Environment.MEDIA_MOUNTED;

public final class FileUtils {

    private static final String TAG = "文件辅助类";

    public static String getFilePathByUri(Context context, Uri uri) {
        Log.i(TAG, "Uri转path 1");
        String path = null;
        // 以 file:// 开头的
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            path = uri.getPath();
            return path;
        }
        Log.i(TAG, "Uri转path 2");
        // 以 content:// 开头的，比如 content://media/external/images/media/101
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            // 普通文件
            if (uri.getPath().contains("/document/")) {
                //  DocumentsProvider
                path = getDocumentPath(context, uri);
            } else if (uri.getPath().contains("media")) {
                // 媒体文件
                path = getMediaPath(context, uri);
            } else {
                // 其他 content URI
                path = getDataColumn(context, uri, null, null);
            }
        }
        return path;
    }

    private static String getDocumentPath(Context context, Uri uri) {
        String docId = DocumentsContract.getDocumentId(uri);
        if (docId.startsWith("raw:")) {
            return docId.replaceFirst("raw:", "");
        }
        String[] split = docId.split(":");
        if (split.length >= 2) {
            String type = split[0];
            Uri contentUri = null;
            if ("image".equals(type)) {
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if ("video".equals(type)) {
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else if ("audio".equals(type)) {
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            }
            if (contentUri != null) {
                String selection = MediaStore.MediaColumns._ID + "=?";
                String[] selectionArgs = new String[]{split[1]};
                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        return null;
    }

    private static String getMediaPath(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                String path = cursor.getString(columnIndex);
                cursor.close();
                return path;
            }
            cursor.close();
        }
        return uri.getPath();
    }

    private static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        String column = MediaStore.MediaColumns.DATA;
        String[] projection = {column};
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(columnIndex);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    // 🔧 修复：使用应用私有目录替代废弃的公共目录，增加 null 安全 + 自动创建目录
    public static String getExternStorageDir(Context context, String strSubDir) {
        File targetDir;
        if (MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: 使用应用私有目录，避免 MANAGE_EXTERNAL_STORAGE 依赖
                File extDir = context.getExternalFilesDir(strSubDir);
                if (extDir != null) {
                    targetDir = extDir;
                } else {
                    // 降级到内部存储
                    targetDir = new File(context.getFilesDir(), strSubDir);
                }
            } else {
                // Android 10 及以下: 使用公共目录
                String stringpackagename = getAppProcessName(context);
                String strdir = stringpackagename + File.separator + strSubDir;
                targetDir = Environment.getExternalStoragePublicDirectory(strdir);
            }
        } else {
            targetDir = new File(context.getFilesDir(), strSubDir);
        }

        // 🔧 自动创建目录
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                Log.w(TAG, "创建目录失败，降级到内部存储: " + targetDir.getAbsolutePath());
                targetDir = new File(context.getFilesDir(), strSubDir);
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
            }
        }

        String directoryPath = targetDir.getAbsolutePath();
        Log.i(TAG, "getExternStorageDir: " + directoryPath);
        return directoryPath;
    }

    // 🔧 修复：安全的文件复制（带校验）
    public static boolean copyStreamContent(InputStream inputStream, OutputStream outputStream) {
        try {
            byte[] buffer = new byte[8192];
            int length;
            long totalBytes = 0;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
                totalBytes += length;
            }
            outputStream.flush();
            Log.i(TAG, "复制完成，共 " + totalBytes + " 字节");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制失败", e);
            return false;
        }
    }

    // 🔧 修复：验证文件是否存在且大小正确
    public static boolean verifyFile(String filePath, long expectedSize) {
        if (TextUtils.isEmpty(filePath)) return false;
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            Log.w(TAG, "文件不存在: " + filePath);
            return false;
        }
        if (expectedSize > 0 && file.length() != expectedSize) {
            Log.w(TAG, "文件大小不匹配: " + filePath + " 期望=" + expectedSize + " 实际=" + file.length());
            return false;
        }
        return true;
    }

    private static String getAppProcessName(Context context) {
        if (context == null) return "unknown";
        return context.getPackageName();
    }
}
