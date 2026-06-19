package com.epai.oblender;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static boolean sNativeLibLoaded = false;

    // Used to load the 'native-lib' library on application startup.
    static {
        try {
            System.loadLibrary("native-lib");
            sNativeLibLoaded = true;
            Log.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError | SecurityException e) {
            sNativeLibLoaded = false;
            Log.w(TAG, "Native library not available (pure Java mode): " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
