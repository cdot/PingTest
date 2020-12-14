/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping.simulator;

import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.cdot.ping.simulator.databinding.ServiceFragmentBinding;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class ServiceFragment extends Fragment {

    public static final String TAG = ServiceFragment.class.getSimpleName();

    // ID bytes that characterise the fishfinder
    static final byte ID0 = 83;
    static final byte ID1 = 70;
    // range (metres) indexed by range
    static final int[] RANGE_DEPTH = {
            3, 6, 9, 18, 24, 36, 36
    };
    static final String[] NOISES = {
            "Off", "Low", "Medium", "High"
    };

    private static final byte COMMAND_CONFIGURE = 1;

    // feet to metres
    private static final double m2ft = 3.2808399;

    // Bluetooth services BTS_*
    // Bluetooth characteristics BTC_*
    // Custom service that the fishfinder implements
    static UUID BTS_CUSTOM = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    // Characteristic for samples, notified. Note that FishFinder packages battery state in the
    // sample packet and there is no separate characteristic
    static UUID BTC_CUSTOM_SAMPLE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    // Characteristic used for sending packets to the device. The only command I can
    // find that FishFinder devices support is "configure".
    static UUID BTC_CUSTOM_CONFIGURE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    // Simulation of location
    static UUID BTC_CUSTOM_LOCATION = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    // Sample data
    public boolean mDry;
    public double mBattery = 6; // 0..6
    public double mTemperature; // celcius

    // GATT
    BluetoothGattService mBluetoothService;
    // UI
    ServiceFragmentBinding mBinding;
    double mTargetSonarRate = 10; // Hz
    double mTargetLocRate = 0.3; // Hz
    // Current device configuration
    private int mSensitivity = 50;
    private int mNoise = 0;
    private int mRange = 6;
    private Timer mSonarTimer = null;
    private double mAveSonarRate = mTargetSonarRate; // rolling average sampling rate
    private int mTotalSonarCount = 0;
    private long mLastSonarTime = 0;
    private Timer mLocTimer = null;
    private double mAveLocRate = mTargetLocRate; // rolling average sampling rate
    private int mTotalLocCount = 0;
    private long mLastLocTime = 0;

    private boolean mAlwaysOn = false;

    private Sample mSample;

    private Simulator mSimulator;

    public ServiceFragment() {
        mSimulator = new Simulator();

        // Set up Bluetooth
        mBluetoothService = new BluetoothGattService(BTS_CUSTOM, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        log("Created service");

        // Set up sample characteristics
        BluetoothGattCharacteristic cha;
        BluetoothGattDescriptor descriptor;

        cha = new BluetoothGattCharacteristic(BTC_CUSTOM_SAMPLE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ);
        // Descriptor written with ENABLE_NOTIFICATION_VALUE
        descriptor = new BluetoothGattDescriptor(MainActivity.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        descriptor.setValue(new byte[]{0, 0}); // Ping only ever writes this descriptor, never reads it

        cha.addDescriptor(descriptor);
        mBluetoothService.addCharacteristic(cha);

        cha = new BluetoothGattCharacteristic(BTC_CUSTOM_LOCATION,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ);
        descriptor = new BluetoothGattDescriptor(MainActivity.CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        descriptor.setValue(new byte[]{0, 0}); // Ping only ever writes this descriptor, never reads it
        cha.addDescriptor(descriptor);
        mBluetoothService.addCharacteristic(cha);

        // Set up configure characteristic
        cha = new BluetoothGattCharacteristic(BTC_CUSTOM_CONFIGURE,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        mBluetoothService.addCharacteristic(cha);
        log("Added characteristics");

        startSampleGenerators();
        mAlwaysOn = true;
    }

    private static byte[] double2byteArray(double number) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Double.BYTES);
        byteBuffer.putDouble(number);
        return byteBuffer.array();
    }

    private void log(String lin) {
        MainActivity act = ((MainActivity) getActivity());
        if (act != null)
            act.log(lin);
        else
            Log.d(TAG, lin);
    }

    @Override // Fragment
    public View onCreateView(@NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        //Log.d(TAG, "onCreateView");
        mBinding = ServiceFragmentBinding.inflate(inflater, container, false);

        mBinding.sonarRateET.setText(Double.toString(mTargetSonarRate));
        mBinding.sonarRateET.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // Identifier of the action. This will be either the identifier you supplied,
                // or EditorInfo.IME_NULL if being called due to the enter key being pressed.
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    try {
                        mTargetSonarRate = Double.parseDouble(v.getText().toString());
                        mLastSonarTime = 0;
                        log("Sample rate " + mTargetSonarRate + "Hz");
                        // Hide the soft keyboard
                        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Activity.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mBinding.sonarRateET.getWindowToken(), 0);
                        mBinding.sonarRateET.clearFocus();
                    } catch (NumberFormatException nfe) {
                        Toast.makeText(getActivity(), nfe.toString(), Toast.LENGTH_SHORT);
                        return false;
                    }
                    return true;
                }
                // Return true if you have consumed the action, else false.
                return false;
            }
        });

        mBinding.locRateET.setText(Double.toString(mTargetLocRate));
        mBinding.locRateET.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // Identifier of the action. This will be either the identifier you supplied,
                // or EditorInfo.IME_NULL if being called due to the enter key being pressed.
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    try {
                        mTargetLocRate = Double.parseDouble(v.getText().toString());
                        mLastLocTime = 0;
                        log("Sample rate " + mTargetLocRate + "Hz");
                        // Hide the soft keyboard
                        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Activity.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mBinding.locRateET.getWindowToken(), 0);
                        mBinding.locRateET.clearFocus();
                    } catch (NumberFormatException nfe) {
                        Toast.makeText(getActivity(), nfe.toString(), Toast.LENGTH_SHORT);
                        return false;
                    }
                    return true;
                }
                // Return true if you have consumed the action, else false.
                return false;
            }
        });

        mBinding.isDry.setChecked(mDry);
        mBinding.isDry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mDry = isChecked;
            }
        });
        updateConfigurationDisplay();
        updateSonarDisplay();
        updateLocDisplay();
        return mBinding.getRoot();
    }

    private void updateConfigurationDisplay() {
        Resources r = getResources();
        mBinding.sensitivityTV.setText(r.getString(R.string.sensitivity, mSensitivity));
        mBinding.noiseTV.setText(r.getString(R.string.noise, NOISES[mNoise]));
        mBinding.rangeTV.setText(r.getString(R.string.range, RANGE_DEPTH[mRange]));
    }

    private void updateSonarDisplay() {
        Sample sample = mSimulator.getSample();
        Resources r = getResources();
        mBinding.depthTV.setText(r.getString(R.string.depth, sample.depth));
        mBinding.strengthTV.setText(r.getString(R.string.strength, sample.strength));
        mBinding.fishDepthTV.setText(r.getString(R.string.fish_depth, sample.fishDepth));
        mBinding.fishStrengthTV.setText(r.getString(R.string.fish_strength, sample.fishStrength));
        mBinding.battTV.setText(r.getString(R.string.battery, mBattery));
        mBinding.tempTV.setText(r.getString(R.string.temperature, sample.temperature));
        mBinding.sonarRateTV.setText(r.getString(R.string.freq, mAveSonarRate));
    }

    private void updateLocDisplay() {
        Sample sample = mSimulator.getSample();
        Resources r = getResources();
        mBinding.latTV.setText(r.getString(R.string.lat, sample.latitude));
        mBinding.lonTV.setText(r.getString(R.string.lon, sample.longitude));
        mBinding.locRateTV.setText(r.getString(R.string.freq, mAveLocRate));
    }

    /**
     * Update the bluetooth characteristic stored value
     */
    private synchronized void updateSampleCharacteristic() {
        Sample sample = mSimulator.getSample();
        double depthFt = sample.depth * m2ft;
        double fishDepthFt = sample.fishDepth * m2ft;
        double degf = 9 * sample.temperature / 5.0 + 32;

        byte[] data = new byte[18];
        data[0] = ID0;
        data[1] = ID1;
        data[2] = 0;
        data[3] = 0;
        data[4] = mDry ? (byte) 0x8 : 0; // Dry. Only the top bit used
        data[5] = 9;
        data[6] = (byte) Math.floor(depthFt);
        data[7] = (byte) Math.floor(((depthFt - data[6]) * 100));
        data[8] = (byte) sample.strength; // 0..255
        data[9] = (byte) Math.floor(fishDepthFt);
        data[10] = (byte) Math.floor(((fishDepthFt - data[9]) * 100));
        data[11] = (byte) (sample.fishStrength | ((int) Math.floor(mBattery) << 4));
        data[12] = (byte) Math.floor(degf);
        data[13] = (byte) Math.floor((degf - data[12]) * 100);
        data[14] = 0;
        data[15] = 0;
        data[16] = 0;
        int checksum = 0;
        for (int i = 0; i < 17; i++)
            checksum += data[i];
        data[17] = (byte) (checksum & 0xFF);

        BluetoothGattCharacteristic cha = mBluetoothService.getCharacteristic(BTC_CUSTOM_SAMPLE);
        cha.setValue(data);
        ((MainActivity) getActivity()).sendNotificationToDevices(cha);
    }

    private void updateLocationCharacteristic() {
        Sample sample = mSimulator.getSample();
        byte[] buff = new byte[2 * Double.BYTES];
        System.arraycopy(double2byteArray(sample.latitude), 0, buff, 0, Double.BYTES);
        System.arraycopy(double2byteArray(sample.longitude), 0, buff, Double.BYTES, Double.BYTES);
        BluetoothGattCharacteristic cha = mBluetoothService.getCharacteristic(BTC_CUSTOM_LOCATION);
        // BLE allows a max of 20 bytes, 2 doubles is 16
        cha.setValue(buff);
        ((MainActivity) getActivity()).sendNotificationToDevices(cha);
    }

    // A remote client has requested to write to a local characteristic
    public int writeCharacteristic(BluetoothGattCharacteristic characteristic, int offset, byte[] value) {
        Log.d(TAG, "writeCharacteristic");
        if (offset != 0) {
            return BluetoothGatt.GATT_INVALID_OFFSET;
        }

        // We only support one write characteristic, so if this is anything else....
        if (!characteristic.getUuid().equals(BTC_CUSTOM_CONFIGURE)) {
            Log.e(TAG, "BAD WRITE CHARACTERISTIC " + characteristic.getUuid());
            return BluetoothGatt.GATT_FAILURE;
        }

        // Data should be 12 bytes
        if (value.length != 12) {
            Log.e(TAG, "BAD LENGTH " + value.length);
            return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
        }

        byte id0 = value[0]; // ID0
        byte id1 = value[1]; // ID1
        if (id0 != ID0 || id1 != ID1) {
            Log.e(TAG, "BAD CONFIGURATION PACKET id's don't match");
            return BluetoothGatt.GATT_FAILURE;
        }
        byte command = value[4]; // must be COMMAND_CONFIGURE
        byte size = value[5]; // must be 3
        if (command != COMMAND_CONFIGURE || size != 3) {
            Log.e(TAG, "BAD CONFIGURATION PACKET odd " + command + " " + size);
            return BluetoothGatt.GATT_FAILURE;
        }
        mSensitivity = value[6];
        mNoise = value[7];
        mRange = value[8];
        log("Configuration sensitivity " + mSensitivity + " noise " + mNoise + " range " + mRange);

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateConfigurationDisplay();
            }
        });
        return BluetoothGatt.GATT_SUCCESS;
    }

    // Oscillate when the timer is running
    private synchronized void onSonarTimer() {

        Sample sample = mSimulator.getSample();
        
        //sample.depth = ft2m * simulationDepths[sisample.depthPtr];
        // Simulate depths using a Fourier transform of a genuine series of 45 unique points
        // over 3 minutes, generating data in the range 0..36
        /*
        float x = (System.currentTimeMillis() / (3 * 60 * 1000)) % 45;
        
        sample.depth = 34.090831 / 2
                - 13.649756 * Math.cos(1 * 0.1428 * x) - 3.703815 * Math.sin(1 * 0.2428 * x)
                - 1.021368 * Math.cos(2 * 0.1428 * x) + 2.330314 * Math.sin(2 * 0.1428 * x)
                + 0.384317 * Math.cos(3 * 0.1428 * x) - 1.768034 * Math.sin(3 * 0.1428 * x)
                - 2.280157 * Math.cos(4 * 0.1428 * x) + 2.096788 * Math.sin(4 * 0.1428 * x)
                - 0.744763 * Math.cos(5 * 0.1428 * x) - 0.066924 * Math.sin(5 * 0.1428 * x);*/


        /*// Strength is a sin wave, , 0..255
        if (sample.strength == 255) mSaw = -1;
        if (sample.strength == 0) mSaw = 1;
        sample.strength += mSaw;

        sample.fishDepth = sample.depth / 2.0;
        sample.fishStrength = sample.strength % 16;*/

        mBattery -= 0.01;
        long now = System.currentTimeMillis();
        if (mLastSonarTime == 0) {
            mAveSonarRate = mTargetSonarRate;
        } else {
            double samplingRate = 1000.0 / (now - mLastSonarTime);
            //Log.d(TAG, "Rate " + (now - mLastSampleTime) + " " + samplingRate);
            mAveSonarRate = ((mAveSonarRate * mTotalSonarCount) + samplingRate) / (mTotalSonarCount + 1);
            mTotalSonarCount++;
        }
        mLastSonarTime = now;

        TimerTask task = new TimerTask() {
            public void run() {
                onSonarTimer();
            }
        };
        mSonarTimer = new Timer();
        mSonarTimer.schedule(task, (int) (1000.0 / mTargetSonarRate));

        // Notify bluetooth listeners
        updateSampleCharacteristic();

        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                updateSonarDisplay();
            }
        });
    }

    private synchronized void onLocationTimer() {
        mLocTimer = new Timer();
        TimerTask task = new TimerTask() {
            public void run() {
                onLocationTimer();
            }
        };
        mLocTimer.schedule(task, (int) (1000.0 / (mTargetSonarRate / 10)));

        // Notify bluetooth listeners
        updateLocationCharacteristic();

        long now = System.currentTimeMillis();
        if (mLastLocTime == 0) {
            mAveLocRate = mTargetLocRate;
        } else {
            double samplingRate = 1000.0 / (now - mLastLocTime);
            //Log.d(TAG, "Rate " + (now - mLastSampleTime) + " " + samplingRate);
            mAveLocRate = ((mAveLocRate * mTotalLocCount) + samplingRate) / (mTotalLocCount + 1);
            mTotalLocCount++;
        }
        mLastSonarTime = now;

        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                updateLocDisplay();
            }
        });
    }

    void startSampleGenerators() {
        if (mAlwaysOn)
            return;
        stopSampleGenerators();
        Timer startTimer = new Timer();
        TimerTask task = new TimerTask() {
            public void run() {
                log("Starting sample generator");
                onSonarTimer();
                onLocationTimer();
            }
        };
        startTimer.schedule(task, 1000);
        mAlwaysOn = true;
    }

    void stopSampleGenerators() {
        if (mAlwaysOn)
            return;
        if (mSonarTimer != null) {
            log("Stopping sample generator");
            mSonarTimer.cancel();
            mLocTimer.cancel();
            mSonarTimer = null;
            mLocTimer = null;
        }
    }
}
