package com.example.eyepedia.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;

import com.example.eyepedia.ActivityResultEvent;
import com.example.eyepedia.EventBus;
import com.example.eyepedia.R;
import com.example.eyepedia.calibration.CalibrationDataStorage;
import com.example.eyepedia.popupactivity.PopupActivity;
import com.example.eyepedia.popupactivity.PopupGravity;
import com.example.eyepedia.popupactivity.PopupResult;
import com.example.eyepedia.popupactivity.PopupType;
import com.example.eyepedia.view.CalibrationViewer;
import com.example.eyepedia.view.GazePathView;
import com.example.eyepedia.view.PointView;
import com.google.android.material.appbar.AppBarLayout;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import camp.visual.gazetracker.GazeTracker;
import camp.visual.gazetracker.callback.CalibrationCallback;
import camp.visual.gazetracker.callback.GazeCallback;
import camp.visual.gazetracker.callback.InitializationCallback;
import camp.visual.gazetracker.callback.StatusCallback;
import camp.visual.gazetracker.constant.CalibrationModeType;
import camp.visual.gazetracker.constant.InitializationErrorType;
import camp.visual.gazetracker.constant.StatusErrorType;
import camp.visual.gazetracker.device.GazeDevice;
import camp.visual.gazetracker.filter.OneEuroFilterManager;
import camp.visual.gazetracker.gaze.GazeInfo;
import camp.visual.gazetracker.state.EyeMovementState;
import camp.visual.gazetracker.state.ScreenState;
import camp.visual.gazetracker.state.TrackingState;
import camp.visual.gazetracker.util.ViewLayoutChecker;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String[] PERMISSIONS = new String[]
            {Manifest.permission.CAMERA};
    private static final int REQ_PERMISSION = 1000;
    private static boolean GazeViewStatus, InitStatus, OnActivated, BtnActivated;
    public static boolean TranslateStatus;
    public static final String PREFS_NAME = "setup";
    Long pressedTime = null;

    private GazeTracker gazeTracker;
    private final ViewLayoutChecker viewLayoutChecker = new ViewLayoutChecker();
    private HandlerThread backgroundThread = new HandlerThread("background");
    private Handler backgroundHandler;

    // RequestCode 선언
    static class RequestCode {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({Setting_Open, Popup_Normal, Popup_Select, Popup_Error, Popup_Image, Load_txtFile})
        public @interface types {}

        public static final int Setting_Open = 0;
        public static final int Popup_Normal = 1;
        public static final int Popup_Select = 2;
        public static final int Popup_Error = 3;
        public static final int Popup_Image = 4;
        public static final int Load_txtFile = 5;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "GazeTracker Version: " + GazeTracker.getVersionName());
        checkPermission();
        initView();
        initHandler();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportFragmentManager().beginTransaction().replace(R.id.fragment_text, new TextFragment()).commit(); // 텍스트 프래그먼트 띄우기

        // 저장된 설정 변수 읽어오기
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        GazeViewStatus = settings.getBoolean("GazeViewStatus", true); // 다시보기
        TranslateStatus = settings.getBoolean("TranslateStatus", true);
        InitStatus = settings.getBoolean("InitStatus", true);
        OnActivated = settings.getBoolean("OnActivated", false);
        BtnActivated = settings.getBoolean("BtnActivated", false);
        //FirstActivated = settings.getBoolean("FirstActivated", true);

        setOffsetOfView();
        Log.i(TAG, "OnCreate");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(getApplicationContext(), SettingActivity.class);
            intent.putExtra("GazeViewStatus", GazeViewStatus);
            intent.putExtra("TranslateStatus", TranslateStatus);
            intent.putExtra("InitStatus", InitStatus);
            startActivityForResult(intent, RequestCode.Setting_Open);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        EventBus.getInstance().post(ActivityResultEvent.create(requestCode, resultCode, data));

        if (resultCode == RESULT_OK) {
            PopupResult result = (PopupResult) data.getSerializableExtra("result");
            switch (requestCode) {
                case RequestCode.Setting_Open :
                    GazeViewStatus = data.getBooleanExtra("GazeViewStatus", false);
                    TranslateStatus = data.getBooleanExtra("TranslateStatus", true);
                    InitStatus = data.getBooleanExtra("InitStatus", true);
                    break;
                case RequestCode.Popup_Normal :
                    if(result == PopupResult.CENTER) {
                        startTracking();
                        startCalibration();
                    }
                    break;

                default :
                    if (getIntent().getBooleanExtra("Clicked", false) && !BtnActivated){
                        showToast( "OnClicked", true);
                        GazeViewStatus = getIntent().getBooleanExtra("GazeViewStatus", false);
                        TranslateStatus = getIntent().getBooleanExtra("TranslateStatus", true);
                        InitStatus = getIntent().getBooleanExtra("InitStatus", true);
                        BtnActivated = !BtnActivated;
                    } else {
                        TextFragment textFragment = (TextFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_text);
                        textFragment.setTextView(ReadTextFile(data.getData()));
                        break;
                    }
            }
        }
    }

    public String ReadTextFile(Uri uri){
        StringBuffer strBuffer = new StringBuffer();
        try{
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line="";
            while((line=reader.readLine())!=null) {
                strBuffer.append(line + "\n");
            }
            reader.close();
            is.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return "FileError";
        } catch (IOException e) {
            e.printStackTrace();
            return "Error";
        }
        return strBuffer.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initView();
        startTracking();
        // 설정 번경시 변경된 변수 받기
        if (getIntent().getBooleanExtra("Clicked", false)){
            GazeViewStatus = getIntent().getBooleanExtra("GazeViewStatus", false);
            TranslateStatus = getIntent().getBooleanExtra("TranslateStatus", true);
            InitStatus = getIntent().getBooleanExtra("InitStatus", true);
        }
        translatedText.setText("번역할 문장을 주시해 주세요!");
        Log.i(TAG, "onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra("ActiveCalibration", false) && !BtnActivated) {
            BtnActivated = !BtnActivated;

            SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("BtnActivated", BtnActivated);

            Intent intent = new Intent(getBaseContext(), PopupActivity.class);
            intent.putExtra("type", PopupType.NORMAL);
            intent.putExtra("gravity", PopupGravity.CENTER);
            intent.putExtra("title", "알림");
            intent.putExtra("content", "빨간 점을 바라봐주세요!\n인식 정확도를 높여주는 기능입니다!!");
            intent.putExtra("buttonCenter", "닫기");
            startActivityForResult(intent, RequestCode.Popup_Normal);
        }
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTracking();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopTracking();

        // 종료 시 설정 변수 저장
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("GazeViewStatus", GazeViewStatus);
        editor.putBoolean("TranslateStatus", TranslateStatus);
        editor.putBoolean("InitStatus", InitStatus);
        editor.putBoolean("OnActivated", false);
        editor.putBoolean("FirstActivated", false);
        editor.putBoolean("BtnActivated", false);

        editor.commit();

        Log.i(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseHandler();
        viewLayoutChecker.releaseChecker();
        releaseGaze();
    }

    @Override
    public void onBackPressed() {
        if ( pressedTime == null ) {
            Toast.makeText(MainActivity.this, " 한 번 더 누르면 종료됩니다." , Toast.LENGTH_LONG).show();
            pressedTime = System.currentTimeMillis();
        }
        else {
            int seconds = (int) (System.currentTimeMillis() - pressedTime); // 종료 시 설정 변수 저장

            if ( seconds > 2000 ) {
                Toast.makeText(MainActivity.this, " 한 번 더 누르면 종료됩니다." , Toast.LENGTH_LONG).show();
                pressedTime = System.currentTimeMillis();
            } else {
                SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
                SharedPreferences.Editor editor = settings.edit();
                editor.putBoolean("GazeViewStatus", GazeViewStatus);
                editor.putBoolean("TranslateStatus", TranslateStatus);
                editor.putBoolean("InitStatus", InitStatus);
                editor.putBoolean("OnActivated", false);
                editor.putBoolean("FirstActivated", false);

                editor.commit();

                moveTaskToBack(true);
                finishAndRemoveTask();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    }

    // handler
    private void initHandler() {
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void releaseHandler() {
        backgroundThread.quitSafely();
    }
    // handler end

    // permission
    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermissions(PERMISSIONS)) {
                requestPermissions(PERMISSIONS, REQ_PERMISSION);
            } else {
                checkPermission(true);
            }
        } else {
            checkPermission(true);
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private boolean hasPermissions(String[] permissions) {
        int result;
        // Check permission status in string array
        for (String perms : permissions) {
            if (perms.equals(Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                if (!Settings.canDrawOverlays(this)) {
                    return false;
                }
            }
            result = ContextCompat.checkSelfPermission(this, perms);
            if (result == PackageManager.PERMISSION_DENIED) {
                // When if unauthorized permission found
                return false;
            }
        }
        // When if all permission allowed
        return true;
    }

    private void checkPermission(boolean isGranted) {
        if (isGranted) {
            permissionGranted();
        } else {
            showToast("not granted permissions", true);
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQ_PERMISSION:
                if (grantResults.length > 0) {
                    boolean cameraPermissionAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    if (cameraPermissionAccepted) {
                        checkPermission(true);
                    } else {
                        checkPermission(false);
                    }
                }
                break;
        }
    }

    private void permissionGranted() {
        initGaze();
    }
    // permission end

    //view
    private View layoutProgress;
    private CoordinatorLayout backgroundLayout;
    private PointView viewPoint;
    private TextView translatedText;
    private CalibrationViewer viewCalibration;
    private AppBarLayout menuBar;
    private GazePathView gazePathView;
    private boolean isUseGazeFilter = true;
    private CalibrationModeType calibrationType = CalibrationModeType.DEFAULT;

    private void initView() {
        backgroundLayout = findViewById(R.id.layout_background);
        layoutProgress = findViewById(R.id.layout_progress);
        layoutProgress.setOnClickListener(null);

        viewPoint = findViewById(R.id.view_point);
        gazePathView = findViewById(R.id.gazePathView);
        viewCalibration = findViewById(R.id.view_calibration);
        menuBar = findViewById(R.id.menu_bar);

        translatedText = findViewById(R.id.translated_text);

        gazePathView.setVisibility((GazeViewStatus)? View.VISIBLE : View.INVISIBLE);

        setOffsetOfView();
    }

    public void setText(String text) {
        translatedText.setText(text);
    }

    private void setOffsetOfView() {
        viewLayoutChecker.setOverlayView(viewPoint, new ViewLayoutChecker.ViewLayoutListener() {
            @Override
            public void getOffset(int x, int y) {
                viewPoint.setOffset(x, y);
                gazePathView.setOffset(x, y);
                viewCalibration.setOffset(x, y);
            }
        });
    }

    private void showProgress() {
        if (layoutProgress != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    layoutProgress.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private void hideProgress() {
        if (layoutProgress != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    layoutProgress.setVisibility(View.INVISIBLE);
                }
            });
        }
    }

    private void showToast(final String msg, final boolean isShort) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, isShort ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showGazePoint(final float x, final float y, final ScreenState type) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewPoint.setType(type == ScreenState.INSIDE_OF_SCREEN ? PointView.TYPE_DEFAULT : PointView.TYPE_OUT_OF_SCREEN);
                viewPoint.setPosition(x, y);
            }
        });
    }

    private void setCalibrationPoint(final float x, final float y) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                menuBar.setVisibility((View.INVISIBLE));
                viewCalibration.setVisibility(View.VISIBLE);
                viewCalibration.changeDraw(true, null);
                viewCalibration.setPointPosition(x, y);
                viewCalibration.setPointAnimationPower(0);
            }
        });
    }

    private void setCalibrationProgress(final float progress) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewCalibration.setPointAnimationPower(progress);
            }
        });
    }

    private void hideCalibrationView() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                menuBar.setVisibility(View.VISIBLE);
                viewCalibration.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void setViewAtGazeTrackerState() {
//        Log.i(TAG, "gaze : " + isGazeNonNull() + ", tracking : " + isTracking());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isTracking()) {
                    hideCalibrationView();
                }
            }
        });
    }
    // view end

    // gazeTracker
    private boolean isTracking() {
        if (isGazeNonNull()) {
            return gazeTracker.isTracking();
        }
        return false;
    }
    private boolean isGazeNonNull() {
        return gazeTracker != null;
    }

    private InitializationCallback initializationCallback = new InitializationCallback() {
        @Override
        public void onInitialized(GazeTracker gazeTracker, InitializationErrorType error) {
            if (gazeTracker != null) {
                initSuccess(gazeTracker);
            } else {
                initFail(error);
            }
        }
    };

    private void initSuccess(GazeTracker gazeTracker) {
        this.gazeTracker = gazeTracker;
        this.gazeTracker.setCallbacks(gazeCallback, calibrationCallback, statusCallback);
        startTracking();
        hideProgress();
        if (InitStatus && !OnActivated) {
            Intent intent = new Intent(getBaseContext(), PopupActivity.class);
            intent.putExtra("type", PopupType.NORMAL);
            intent.putExtra("gravity", PopupGravity.CENTER);
            intent.putExtra("title", "알림");
            intent.putExtra("content", "빨간 점을 바라봐주세요!\n인식 정확도를 높여주는 기능입니다!!");
            intent.putExtra("buttonCenter", "닫기");
            startActivityForResult(intent, RequestCode.Popup_Normal);

            OnActivated = true;
            SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("OnActivated", OnActivated);
        }
    }

    private void initFail(InitializationErrorType error) {
        String err = "";
        if (error == InitializationErrorType.ERROR_INIT) {
            // When initialization is failed
            err = "Initialization failed";
        } else if (error == InitializationErrorType.ERROR_CAMERA_PERMISSION) {
            // When camera permission doesn not exists
            err = "Required permission not granted";
        } else if (error == InitializationErrorType.AUTH_INVALID_KEY) {
            // Authentication failure (Invalid License Key)
            err = "Invalid License Key";
        } else if (error == InitializationErrorType.AUTH_INVALID_ENV_USED_DEV_IN_PROD) {
            // Using Dev Key in Prod Environment
            err = "Authentication failed (Dev Key in Prod Environment)";
        } else if (error == InitializationErrorType.AUTH_INVALID_ENV_USED_PROD_IN_DEV) {
            // Using Prod Key in Dev Environment
            err = "Authentication failed (Prod Key in Dev Environment)";
        } else if (error == InitializationErrorType.AUTH_INVALID_PACKAGE_NAME) {
            // Invalide Package Name
            err = "Invalid Package Name";
        } else if (error == InitializationErrorType.AUTH_INVALID_APP_SIGNATURE) {
            // Invalid App Signature
            err = "Invalid App Signature";
        } else if (error == InitializationErrorType.AUTH_EXCEEDED_FREE_TIER) {
            // Free Tier limit is exceeded
            err = "Free Tier limit is exceeded";
        } else if (error == InitializationErrorType.AUTH_DEACTIVATED_KEY) {
            // Deactivated License Key
            err = "Authentication failed (Deactivated License Key)";
        } else if (error == InitializationErrorType.AUTH_INVALID_ACCESS) {
            // Invalid Access Method
            err = "Invalid Access Method";
        } else if (error == InitializationErrorType.AUTH_UNKNOWN_ERROR) {
            // Unknown Error
            err = "Unknown Error";
        } else if (error == InitializationErrorType.AUTH_SERVER_ERROR) {
            // Server Authentication Error
            err = "Server Authentication failed";
        } else if (error == InitializationErrorType.AUTH_CANNOT_FIND_HOST) {
            // Host Server Connection failed
            err = "Host Server Connection failed";
        } else if (error == InitializationErrorType.AUTH_WRONG_LOCAL_TIME) {
            // Local and server time does not match
            err = "Local device time does not match with Server";
        } else if (error == InitializationErrorType.AUTH_INVALID_KEY_FORMAT) {
            // Invalide License Key Format
            err = "Invalid License Key Format";
        } else {
            // Gaze library initialization failure
            // It can ba caused by several reasons(i.e. Out of memory).
            err = "init gaze library fail";
        }
        Log.w(TAG, "error description: " + err);
    }

    private Long activeTime = null;
    private final OneEuroFilterManager oneEuroFilterManager = new OneEuroFilterManager(2);
    private GazeCallback gazeCallback = new GazeCallback() {
        @Override
        public void onGaze(GazeInfo gazeInfo) {
            if (isGazeNonNull()) {
                TrackingState state = gazeInfo.trackingState;
                if (state == TrackingState.SUCCESS) {
                    if (!gazeTracker.isCalibrating()) {
                        if (isUseGazeFilter) {
                            if (oneEuroFilterManager.filterValues(gazeInfo.timestamp, gazeInfo.x, gazeInfo.y)) {
                                float[] filteredPoint = oneEuroFilterManager.getFilteredValues();
                                gazePathView.onGaze(filteredPoint[0], filteredPoint[1], gazeInfo.eyeMovementState == EyeMovementState.FIXATION);
                                long curTime = System.currentTimeMillis();
                                if (gazePathView.isLongFixation(curTime)) {
                                    if (activeTime == null) {
                                        activeTime = curTime;
                                    } else if (!(curTime - activeTime < 1000)) {
                                        activeTime = curTime;
                                        // Obtain MotionEvent object
                                        long downTime = SystemClock.uptimeMillis();
                                        long eventTime = SystemClock.uptimeMillis() + 100;
                                        float x = (gazeInfo.screenState == ScreenState.INSIDE_OF_SCREEN ? filteredPoint[0] : 0.0f);
                                        float y = (gazeInfo.screenState == ScreenState.INSIDE_OF_SCREEN ? filteredPoint[1] : 0.0f);
                                        // List of meta states found here: developer.android.com/reference/android/view/KeyEvent.html#getMetaState()
                                        int metaState = 0;

                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                backgroundLayout.dispatchTouchEvent(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, metaState));
                                                backgroundLayout.dispatchTouchEvent(MotionEvent.obtain(downTime + 100, eventTime + 200, MotionEvent.ACTION_UP, x, y, metaState));
                                            }
                                        });
                                        //Log.i(TAG, "GazeTracker x: " + x + " GazeTracker y: " + y);
                                    }
                                }
                            }
                        } else {
                            showGazePoint(gazeInfo.x, gazeInfo.y, gazeInfo.screenState);
                        }
                    }
                }
            }
        }
    };

    private CalibrationCallback calibrationCallback = new CalibrationCallback() {
        @Override
        public void onCalibrationProgress(float progress) {
            setCalibrationProgress(progress);
        }

        @Override
        public void onCalibrationNextPoint(final float x, final float y) {
            setCalibrationPoint(x, y);
            // Give time to eyes find calibration coordinates, then collect data samples
            backgroundHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startCollectSamples();
                }
            }, 1000);
        }

        @Override
        public void onCalibrationFinished(double[] calibrationData) {
            // 캘리브레이션이 끝나면 자동으로 gazepoint에 적용되어있고
            // calibrationDataStorage에 calibrationData를 넣는것은 다음번에 캘리브레이션 하지않고 사용하게 하기 위함이다.
            CalibrationDataStorage.saveCalibrationData(getApplicationContext(), calibrationData);
            hideCalibrationView();
            showToast("calibrationFinished", true);
        }
    };

    private StatusCallback statusCallback = new StatusCallback() {
        @Override
        public void onStarted() {
            // isTracking true
            // When if camera stream starting
        }

        @Override
        public void onStopped(StatusErrorType error) {
            // isTracking false
            // When if camera stream stopping
            if (error != StatusErrorType.ERROR_NONE) {
                switch (error) {
                    case ERROR_CAMERA_START:
                        // When if camera stream can't start
                        showToast("ERROR_CAMERA_START ", false);
                        break;
                    case ERROR_CAMERA_INTERRUPT:
                        // When if camera stream interrupted
                        showToast("ERROR_CAMERA_INTERRUPT ", false);
                        break;
                }
            }
        }
    };

    private void initGaze() {
        showProgress();
        GazeDevice gazeDevice = new GazeDevice();
        if (gazeDevice.isCurrentDeviceFound()) {
            // 돌린 기기의 device info가 있는지확인
            Log.d(TAG, "이 디바이스는 gazeinfo 설정이 필요 없습니다.");
        } else {
            // 예시입니다. SM-T720은 갤럭시탭 s5e 모델명
            gazeDevice.addDeviceInfo("SM-T720", -72f, -4f);
        }

        String licenseKey = "dev_6223o6dqrnnywdbl55htd5mr26jv9fi08qskthlp";
        GazeTracker.initGazeTracker(getApplicationContext(), gazeDevice, licenseKey, initializationCallback);
    }

    private void releaseGaze() {
        if (isGazeNonNull()) {
            GazeTracker.deinitGazeTracker(gazeTracker);
            gazeTracker = null;
        }
    }

    private void startTracking() {
        if (isGazeNonNull()) {
            gazeTracker.startTracking();
        }
    }

    private void stopTracking() {
        if (isGazeNonNull()) {
            gazeTracker.stopTracking();
        }
    }

    private boolean startCalibration() {
        boolean isSuccess = false;
        if (isGazeNonNull()) {
            isSuccess = gazeTracker.startCalibration(calibrationType);
            if (!isSuccess) {
                showToast("calibration start fail", false);
            }
        }
        setViewAtGazeTrackerState();
        return isSuccess;
    }

    // Collect the data samples used for calibration
    private boolean startCollectSamples() {
        boolean isSuccess = false;
        if (isGazeNonNull()) {
            isSuccess = gazeTracker.startCollectSamples();
        }
        setViewAtGazeTrackerState();
        return isSuccess;
    }
}
