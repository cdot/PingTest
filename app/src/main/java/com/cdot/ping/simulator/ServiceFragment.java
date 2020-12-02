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
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.cdot.ping.simulator.databinding.ServiceFragmentBinding;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class ServiceFragment extends Fragment {

    public static final String TAG = ServiceFragment.class.getSimpleName();

    // ID bytes that characterise the fishfinder
    static final byte ID0 = 83;
    static final byte ID1 = 70;
    private static final byte COMMAND_CONFIGURE = 1;

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

    // GATT
    BluetoothGattService mBluetoothService;

    // feet to metres
    private static final double m2ft = 3.2808399;

    // range (metres) indexed by range
    static final int[] RANGE_DEPTH = {
            3, 6, 9, 18, 24, 36, 36
    };

    static final String[] NOISES = {
            "Off", "Low", "Medium", "High"
    };

    // Current device configuration
    private int mSensitivity = 50;
    private int mNoise = 0;
    private int mRange = 6;

    // Sample data
    public double mDepth; // metres
    public int mStrength; // strength of bottom signal, 0-255
    public double mFishDepth; // metres
    public int mFishStrength; // Strength of fish return signal, 0-16
    public double mBattery = 6; // 0..6
    public double mTemperature; // celcius

    // Timer used for sample generation
    private Timer mTimer = null;

    // UI
    ServiceFragmentBinding mBinding;

    static final int DEFAULT_SAMPLE_RATE = 5000; // ms

    int mSampleRate = DEFAULT_SAMPLE_RATE;

    public ServiceFragment() {
        // Set up Bluetooth
        mBluetoothService = new BluetoothGattService(BTS_CUSTOM, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Set up sample characteristic
        BluetoothGattCharacteristic cha = new BluetoothGattCharacteristic(BTC_CUSTOM_SAMPLE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ);

        BluetoothGattDescriptor descriptor;

        // Descriptor written with ENABLE_NOTIFICATION_VALUE
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

        mTimer = new Timer();
    }

    private void log(String lin) {
        ((MainActivity) getActivity()).log(lin);
    }

    @Override // Fragment
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        //Log.d(TAG, "onCreateView");
        mBinding = ServiceFragmentBinding.inflate(inflater, container, false);

        mBinding.editTextSampleFrequency.setText(Integer.toString(mSampleRate));
        mBinding.editTextSampleFrequency.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // Identifier of the action. This will be either the identifier you supplied,
                // or EditorInfo.IME_NULL if being called due to the enter key being pressed.
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    mSampleRate = Integer.parseInt(v.getText().toString());
                    log("Sample rate " + mSampleRate + "ms");
                    // Hide the soft keyboard
                    InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Activity.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(mBinding.editTextSampleFrequency.getWindowToken(), 0);
                    mBinding.editTextSampleFrequency.clearFocus();
                    return true;
                }
                // Return true if you have consumed the action, else false.
                return false;
            }
        });
        updateConfigurationDisplay();
        updateSampleDisplay();
        return mBinding.getRoot();
    }

    private void updateConfigurationDisplay() {
        Resources r = getResources();
        mBinding.textViewSensitivity.setText(r.getString(R.string.sensitivity, mSensitivity));
        mBinding.textViewNoise.setText(r.getString(R.string.noise, NOISES[mNoise]));
        mBinding.textViewRange.setText(r.getString(R.string.range, RANGE_DEPTH[mRange]));
    }

    private void updateSampleDisplay() {
        Resources r = getResources();
        mBinding.textViewDepth.setText(r.getString(R.string.depth, mDepth));
        mBinding.textViewStrength.setText(r.getString(R.string.strength, mStrength));
        mBinding.textViewFishDepth.setText(r.getString(R.string.fish_depth, mFishDepth));
        mBinding.textViewFishStrength.setText(r.getString(R.string.fish_strength, mFishStrength));
        mBinding.textViewBattery.setText(r.getString(R.string.battery, mBattery));
        mBinding.textViewTemperature.setText(r.getString(R.string.temperature, mTemperature));
        mBinding.textViewSampleCount.setText(r.getString(R.string.sample_count, mSampleCount));
    }

    /**
     * Update the bluetooth characteristic stored value
     */
    private void updateSampleCharacteristic() {
        double depthFt = mDepth * m2ft;
        double fishDepthFt = mFishDepth * m2ft;
        double degf = 9 * mTemperature / 5.0 + 32;

        byte[] data = new byte[14];
        data[0] = ID0;
        data[1] = ID1;
        //data[2]
        //data[3]
        data[4] = (mDepth <= 0) ? (byte) 0x8 : 0; // Dry? Only the top bit used
        //data[5]
        data[6] = (byte) Math.floor(depthFt);
        data[7] = (byte) Math.floor(((depthFt - data[6]) * 100));
        data[8] = (byte) mStrength; // 0..255
        data[9] = (byte) Math.floor(fishDepthFt);
        data[10] = (byte) Math.floor(((fishDepthFt - data[9]) * 100));
        data[11] = (byte) (mFishStrength | ((int)Math.floor(mBattery) << 4));
        data[12] = (byte) Math.floor(degf);
        data[13] = (byte) Math.floor((degf - data[12]) * 100);

        BluetoothGattCharacteristic cha = mBluetoothService.getCharacteristic(BTC_CUSTOM_SAMPLE);
        cha.setValue(data);
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

        // Remaining bytes should be 0

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateConfigurationDisplay();
            }
        });
        return BluetoothGatt.GATT_SUCCESS;
    }

    private int mSampleCount = 0;
    // Step variables used for sample generation
    private int mDeg = 0;
    private int mSaw = 1;

    // Oscillate when the timer is running
    private void onSampleTimer() {
        mDeg = (mDeg + 1) % 360;

        // Depth is a sin wave
        mDepth = RANGE_DEPTH[mRange] / 2.0 + RANGE_DEPTH[mRange] * Math.sin(mDeg * Math.PI / 180.0) / 2.0;

        // Strength is a sawtooth wave, 0..255
        if (mStrength == 255) mSaw = -1;
        if (mStrength == 0) mSaw = 1;
        mStrength += mSaw;

        mFishDepth = mDepth / 2.0;
        mFishStrength = mStrength % 16;

        mBattery -= 0.01;
        mTemperature = (float) (20.0 + 15.0 * Math.cos(mDeg * Math.PI / 180.0));

        mSampleCount++;

        // Notify bluetooth listeners
        updateSampleCharacteristic();
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                updateSampleDisplay();
            }
        });
        TimerTask task = new TimerTask() {
            public void run() {
                onSampleTimer();
            }
        };
        mTimer.schedule(task, mSampleRate); // ms
    }

    void startSampleGenerator() {
        log("Starting sample generator");
        TimerTask task = new TimerTask() {
            public void run() {
                onSampleTimer();
            }
        };
        mTimer.schedule(task, mSampleRate); // ms
    }

    void stopSampleGenerator() {
        log("Stopping sample generator");
        if (mTimer != null)
            mTimer.cancel();
    }
}
