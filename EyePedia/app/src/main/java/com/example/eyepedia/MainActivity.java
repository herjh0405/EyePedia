package com.example.eyepedia;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.example.eyepedia.view.CalibrationDataStorage;
import com.example.eyepedia.view.CalibrationViewer;
import com.example.eyepedia.view.PointView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

import camp.visual.gazetracker.*;
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
import camp.visual.gazetracker.state.ScreenState;
import camp.visual.gazetracker.util.ViewLayoutChecker;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String[] PERMISSIONS = new String[]
            {Manifest.permission.CAMERA};
    private static final int REQ_PERMISSION = 1000;

    private GazeTracker gazeTracker;
    private ViewLayoutChecker viewLayoutChecker = new ViewLayoutChecker();
    private HandlerThread backgroundThread = new HandlerThread("background");
    private Handler backgroundHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "GazeTracker Version: " + GazeTracker.getVersionName());
        checkPermission();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
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

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermissions(PERMISSIONS)) {
                requestPermissions(PERMISSIONS, REQ_PERMISSION);
            } else {
                checkPermission(true);
            }
        }else{
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

    private void permissionGranted() { initGaze(); }

    private TextureView preview;
    private View layoutProgress;
    private View viewWarningTracking;
    private PointView viewPoint;
    private Button btnInitGaze, btnReleaseGaze;
    private Button btnStartTracking, btnStopTracking;
    private Button btnStartCalibration, btnStopCalibration, btnSetCalibration;
    private CalibrationViewer viewCalibration;
    private CalibrationModeType calibrationType = CalibrationModeType.DEFAULT;

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
        public void onInitialized(GazeTracker gazeTracker, InitializationErrorType initializationErrorType) {
            if (gazeTracker != null) {
                initSuccess(gazeTracker);
            } else {
                initFail(initializationErrorType);
            }
        }
    };

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
                viewCalibration.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void setViewAtGazeTrackerState() {
//        Log.i(TAG, "gaze : " + isGazeNonNull() + ", tracking " + isTracking());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btnInitGaze.setEnabled(!isGazeNonNull());
                btnReleaseGaze.setEnabled(isGazeNonNull());
                btnStartTracking.setEnabled(isGazeNonNull() && !isTracking());
                btnStopTracking.setEnabled(isGazeNonNull() && isTracking());
                btnStartCalibration.setEnabled(isGazeNonNull() && isTracking());
                btnStopCalibration.setEnabled(isGazeNonNull() && isTracking());
                btnSetCalibration.setEnabled(isGazeNonNull());
                if (!isTracking()) {
                    hideCalibrationView();
                }
            }
        });
    }

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
            setViewAtGazeTrackerState();
        }

        @Override
        public void onStopped(StatusErrorType error) {
            // isTracking false
            // When if camera stream stopping
            setViewAtGazeTrackerState();
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

    private void initSuccess(GazeTracker gazeTracker) {
        this.gazeTracker = gazeTracker;
        if (preview.isAvailable()) {
            // When if textureView available
            setCameraPreview(preview);
        }
        this.gazeTracker.setCallbacks(gazeCallback, calibrationCallback, statusCallback);
        startTracking();
        hideProgress();
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
        } else  {
            // Gaze library initialization failure
            // It can ba caused by several reasons(i.e. Out of memory).
            err = "init gaze library fail";
        }
        Log.w(TAG, "error description: " + err);
    }

    private OneEuroFilterManager oneEuroFilterManager = new OneEuroFilterManager(2); // x, y
    private GazeCallback gazeCallback = new GazeCallback() {
        @Override
        public void onGaze(GazeInfo gazeInfo) {
            Log.i(TAG, "gaze coord " + gazeInfo.x + "x" + gazeInfo.y);
            if (oneEuroFilterManager.filterValues(gazeInfo.timestamp, gazeInfo.x, gazeInfo.y)) {
                float[] filteredValues = oneEuroFilterManager.getFilteredValues();
                float filteredX = filteredValues[0];
                float filteredY = filteredValues[1];
                Log.i(TAG, "gaze filterd coord " + filteredX + "x" + filteredY);
            }
        }
    };

    private void showToast(final String msg, final boolean isShort) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, isShort ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
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

    private void showTrackingWarning() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewWarningTracking.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideTrackingWarning() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewWarningTracking.setVisibility(View.INVISIBLE);
            }
        });
    }

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
        setViewAtGazeTrackerState();
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

    private void stopCalibration() {
        if (isGazeNonNull()) {
            gazeTracker.stopCalibration();
        }
        hideCalibrationView();
        setViewAtGazeTrackerState();
    }

    private void setCalibration() {
        if (isGazeNonNull()) {
            double[] calibrationData = CalibrationDataStorage.loadCalibrationData(getApplicationContext());
            if (calibrationData != null) {
                // When if stored calibration data in SharedPreference
                if (!gazeTracker.setCalibrationData(calibrationData)) {
                    showToast("calibrating", false);
                } else {
                    showToast("setCalibrationData success", false);
                }
            } else {
                // When if not stored calibration data in SharedPreference
                showToast("Calibration data is null", true);
            }
        }
        setViewAtGazeTrackerState();
    }

    private void setCameraPreview(TextureView preview) {
        if (isGazeNonNull()) {
            gazeTracker.setCameraPreview(preview);
        }
    }

    private void removeCameraPreview() {
        if (isGazeNonNull()) {
            gazeTracker.removeCameraPreview();
        }
    }
}