package com.cdot.ping.simulator;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.cdot.ping.simulator.databinding.MainActivityBinding;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();

    // Generic Bluetooth UUIDs
    public static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");

    class GattServerCallback extends BluetoothGattServerCallback {

        // A device has changed state.
        @Override // BluetoothGattServerCallback
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    mBluetoothDevices.add(device);
                    updateConnectedDevicesStatus();
                    log("Connected to " + device.getAddress());
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    log("Disconnected from " + device.getAddress());
                    mBluetoothDevices.remove(device);
                    updateConnectedDevicesStatus();
                } else {
                    Log.d(TAG, "onConnectionStateChange received new state " + newState);
                }
            } else {
                // There are too many gatt errors (some of them not even in the documentation) so we just
                // show the error to the user.
                final String errorMessage = "onConnectionStateChange error: " + status;
                log(errorMessage);
                Log.e(TAG, errorMessage);
                mBluetoothDevices.remove(device);
                updateConnectedDevicesStatus();
            }
        }

        @Override // BluetoothGattServerCallback
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            String mess = device.getAddress() + " is reading characteristic " + characteristic.getUuid();
            Log.d(TAG, mess + " Value: " + Arrays.toString(characteristic.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.getValue());
        }

        @Override // BluetoothGattServerCallback
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            //Log.v(TAG, "Notification sent. Status: " + status);
            // When multiple notifications are to be sent, an application must wait for this
            // callback to be received before sending additional notifications. In this application
            // we don't bother, but could use GattQueue here.
        }

        // A remote client has requested to write to a local characteristic
        @Override // BluetoothGattServerCallback
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value);
            String mess = device.getAddress() + " is writing characteristic " + characteristic.getUuid();
            log(mess);
            Log.d(TAG, mess + " Value: " + Arrays.toString(value));
            int status = mServiceFragment.writeCharacteristic(characteristic, offset, value);
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
                        /* No need to respond with an offset */ 0,
                        /* No need to respond with a value */ null);
            }
        }

        // A remote client has requested to read a local descriptor.
        @Override // BluetoothGattServerCallback
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            String mess = device.getAddress() + " is reading descriptor " + descriptor.getUuid();
            Log.d(TAG, mess + " Value: " + Arrays.toString(descriptor.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    descriptor.getValue());
        }

        // A remote client has requested to write to a local descriptor.
        @Override // BluetoothGattServerCallback
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
                    offset, value);
            String mess = device.getAddress() + " is writing descriptor " + descriptor.getUuid();
            log(mess);
            Log.d(TAG, mess + " Value: " + Arrays.toString(value));
            int status;
            if (descriptor.getUuid() == MainActivity.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                boolean supportsNotifications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean supportsIndications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (!(supportsNotifications || supportsIndications)) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                } else if (value.length != 2) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    if (characteristic.getUuid() == ServiceFragment.BTC_CUSTOM_SAMPLE) {
                        log("Notifications disabled");
                        mServiceFragment.stopSampleGenerator();
                    }
                    descriptor.setValue(value);
                } else if (supportsNotifications && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    if (characteristic.getUuid() == ServiceFragment.BTC_CUSTOM_SAMPLE) {
                        log("Notifications enabled");
                        mServiceFragment.startSampleGenerator();
                    }
                    descriptor.setValue(value);
                } else if (supportsIndications && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    if (characteristic.getUuid() == ServiceFragment.BTC_CUSTOM_SAMPLE) {
                        log("Indications enabled");
                        mServiceFragment.startSampleGenerator();
                    }
                    descriptor.setValue(value);
                } else {
                    log("Request not supported");
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                }
            } else {
                status = BluetoothGatt.GATT_SUCCESS;
                descriptor.setValue(value);
            }
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
                        /* No need to respond with offset */ 0,
                        /* No need to respond with a value */ null);
            }
        }

        /**
         * Indicates whether a local service has been added successfully.
         *
         * @param status  Returns BluetoothGatt#GATT_SUCCESS if the service was added successfully.
         * @param service The service that has been added
         */
        @Override  // BluetoothGattServerCallback
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG,"Service added " + service.getUuid());
                for (BluetoothGattCharacteristic blech : service.getCharacteristics()) {
                    Log.d(TAG, "Characteristic " + blech.getUuid());
                }
            } else
                log("Error: Service NOT added " + service.getUuid());
        }
    }
    
    // GAP https://learn.adafruit.com/introduction-to-bluetooth-low-energy/gap
    private AdvertiseData mAdvertiseData;
    private AdvertiseData mAdvertiseScanResponse;
    private AdvertiseSettings mAdvertiseSettings;
    private BluetoothLeAdvertiser mAdvertiser;

    // Bluetooth LE advertising callback, used to deliver advertising operation status
    private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override // AdvertiseCallback
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            String statusText;
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    statusText = "ADVERTISE_FAILED_ALREADY_STARTED";
                    break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    statusText = "ADVERTISE_FAILED_DATA_TOO_LARGE";
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    statusText = "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    statusText = "ADVERTISE_FAILED_INTERNAL_ERROR";
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    statusText = "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
                    break;
                default:
                    statusText = "Not advertising: error " + errorCode;
            }
            Log.e(TAG, statusText);
            mBinding.textViewAdvertisingStatus.setText(getResources().getString(R.string.advertising_status, statusText));
        }

        @Override // AdvertiseCallback
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.v(TAG, "Broadcasting");
            mBinding.textViewAdvertisingStatus.setText(getResources().getString(R.string.advertising_status, "OK"));
        }
    };

    // GATT
    private HashSet<BluetoothDevice> mBluetoothDevices = new HashSet<>();
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGattServer mGattServer;

    private final BluetoothGattServerCallback mGattServerCallback = new GattServerCallback();

    private ServiceFragment mServiceFragment;

    // UI
    MainActivityBinding mBinding;

    private int logLines = 0;

    void log(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String cur = mBinding.log.getText().toString();
                if (logLines > 500) {
                    cur = cur.replaceFirst("^.*?\n", "");
                }
                mBinding.log.setText(cur + "\n" + line);
            }
        });
    }

    @Override // AppCompatActivity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mBinding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mBinding.log.setMovementMethod(new ScrollingMovementMethod());

        // Create GATT services
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // Not doable since Marshmallow. Address is hidden, and appears randomly generated on the
        // listening device.
        //Log.d(TAG, "Device address " + mBluetoothAdapter.getAddress());

        // If we are not being restored from a previous state then create and add the service fragment.
        if (savedInstanceState == null) {
            mServiceFragment = new ServiceFragment();

            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.fragment_container, mServiceFragment, TAG)
                    .commit();
        } else {
            mServiceFragment = (ServiceFragment) getSupportFragmentManager()
                    .findFragmentByTag(TAG);
        }

        // GAP
        mAdvertiseSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build();
        mAdvertiseData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(new ParcelUuid(ServiceFragment.BTS_CUSTOM))
                .build();
        mAdvertiseScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
    }

    @Override // AppCompatActivity
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        resetStatusViews();
        // If the user disabled Bluetooth when the app was in the background,
        // openGattServer() will return null.
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mGattServer == null) {
            ensureBleFeaturesAvailable();
            return;
        }
        // Add a service for a total of three services (Generic Attribute and Generic Access
        // are present by default).
        mGattServer.addService(mServiceFragment.mBluetoothService);

        if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            mAdvertiser.startAdvertising(mAdvertiseSettings, mAdvertiseData, mAdvertiseScanResponse, mAdvertiseCallback);
        } else {
            mBinding.textViewAdvertisingStatus.setText(R.string.advertising_unavailable);
        }
    }

    @Override // AppCompatActivity
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true /* show menu */;
    }

    @Override // AppCompatActivity
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected");
        if (item.getItemId() == R.id.action_disconnect_devices) {
            Log.d(TAG, "Disconnecting devices...");
            for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT)) {
                Log.d(TAG, "Devices: " + device.getAddress() + " " + device.getName());
                mGattServer.cancelConnection(device);
            }
            return true /* event_consumed */;
        }
        return false /* event_consumed */;
    }

    @Override // AppCompatActivity
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
        if (mGattServer != null) {
            mGattServer.close();
        }
        if (mBluetoothAdapter.isEnabled() && mAdvertiser != null) {
            // If stopAdvertising() gets called before close() a null
            // pointer exception is raised.
            mAdvertiser.stopAdvertising(mAdvertiseCallback);
        }
        resetStatusViews();
    }

    public void sendNotificationToDevices(BluetoothGattCharacteristic characteristic) {
        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;
        for (BluetoothDevice device : mBluetoothDevices) {
            //Log.d(TAG, "send notification to " + device.getAddress());
            // true for indication (acknowledge) and false for notification (unacknowledge).
            mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
        }
    }

    private void resetStatusViews() {
        mBinding.textViewAdvertisingStatus.setText(R.string.advertising_off);
        updateConnectedDevicesStatus();
    }

    private void updateConnectedDevicesStatus() {
        List<BluetoothDevice> connected = mBluetoothManager.getConnectedDevices(BluetoothGattServer.GATT);
        StringBuilder mess = new StringBuilder();
        for (BluetoothDevice dev : connected) {
            if (mess.length() > 0)
                mess.append(", ");
            mess.append(dev.getName());
        }
        final int nConnected = connected.size();
        final String message = mess.toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.textViewConnectionStatus.setText(getResources().getString(R.string.devices_connected, nConnected, message));
            }
        });
        if (nConnected == 0 && mServiceFragment != null)
            mServiceFragment.stopSampleGenerator();
    }

    private void ensureBleFeaturesAvailable() {
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.bt_not_supported, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Bluetooth not supported");
            finish();
        } else if (!mBluetoothAdapter.isEnabled()) {
            // Make sure bluetooth is enabled.
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);

            final ActivityResultLauncher<Intent> launcher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            if (result.getResultCode() == RESULT_OK) {
                                if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
                                    Toast.makeText(MainActivity.this, R.string.bt_advertising_unsupported, Toast.LENGTH_LONG).show();
                                    Log.e(TAG, "Advertising not supported");
                                }
                                onStart();
                            } else {
                                Toast.makeText(MainActivity.this, R.string.bt_not_enabled, Toast.LENGTH_LONG).show();
                                Log.e(TAG, "Bluetooth not enabled");
                                finish();
                            }
                        }
                    });
            enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launcher.launch(enableBtIntent);
        }
    }
}
