package org.thoughtcrime.securesms.webrtc.audio;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.util.ServiceUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Note: We will need to start handling new permissions once we move to target API 31
 */
@SuppressLint("MissingPermission")
public class BluetoothStateManager {

  private static final String TAG                         = Log.tag(BluetoothStateManager.class);
  private static final int    MAX_SCO_CONNECTION_ATTEMPTS = 2;

  private enum ScoConnection {
    DISCONNECTED,
    IN_PROGRESS,
    CONNECTED
  }

  private final Object LOCK = new Object();

  private final Context                     context;
  private final BluetoothAdapter            bluetoothAdapter;
  private       BluetoothScoReceiver        bluetoothScoReceiver;
  private       BluetoothConnectionReceiver bluetoothConnectionReceiver;
  private final BluetoothStateListener      listener;
  private final AtomicBoolean               destroyed;

  private volatile ScoConnection scoConnection         = ScoConnection.DISCONNECTED;
  private          int           scoConnectionAttempts = 0;

  private BluetoothHeadset bluetoothHeadset = null;
  private boolean          wantsConnection  = false;

  public BluetoothStateManager(@NonNull Context context, @Nullable BluetoothStateListener listener) {
    this.context = context.getApplicationContext();

    BluetoothAdapter localAdapter = BluetoothAdapter.getDefaultAdapter();
    if (localAdapter == null) {
      this.bluetoothAdapter = null;
      this.listener         = null;
      this.destroyed        = new AtomicBoolean(true);
      return;
    }

    this.bluetoothAdapter            = localAdapter;
    this.bluetoothScoReceiver        = new BluetoothScoReceiver();
    this.bluetoothConnectionReceiver = new BluetoothConnectionReceiver();
    this.listener                    = listener;
    this.destroyed                   = new AtomicBoolean(false);

    requestHeadsetProxyProfile();

    this.context.registerReceiver(bluetoothConnectionReceiver, new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED));

    Intent sticky = this.context.registerReceiver(bluetoothScoReceiver, new IntentFilter(getScoChangeIntent()));

    if (sticky != null) {
      bluetoothScoReceiver.onReceive(context, sticky);
    }

    handleBluetoothStateChange();
  }

  public void onDestroy() {
    destroyed.set(true);

    if (bluetoothHeadset != null && bluetoothAdapter != null) {
      this.bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
    }

    if (bluetoothConnectionReceiver != null) {
      context.unregisterReceiver(bluetoothConnectionReceiver);
      bluetoothConnectionReceiver = null;
    }

    if (bluetoothScoReceiver != null) {
      context.unregisterReceiver(bluetoothScoReceiver);
      bluetoothScoReceiver = null;
    }

    this.bluetoothHeadset = null;
  }

  public void setWantsConnection(boolean enabled) {
    synchronized (LOCK) {
      AudioManager audioManager = ServiceUtil.getAudioManager(context);

      this.wantsConnection = enabled;

      if (wantsConnection && isBluetoothAvailable() && scoConnection == ScoConnection.DISCONNECTED) {
        if (scoConnectionAttempts > MAX_SCO_CONNECTION_ATTEMPTS) {
          Log.w(TAG, "We've already attempted to start SCO too many times.  Won't try again.");
        } else {
          scoConnectionAttempts++;
          audioManager.startBluetoothSco();
          scoConnection = ScoConnection.IN_PROGRESS;  
        }
      } else if (!wantsConnection && scoConnection == ScoConnection.CONNECTED) {
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);
        scoConnection = ScoConnection.DISCONNECTED;
      } else if (!wantsConnection && scoConnection == ScoConnection.IN_PROGRESS) {
        audioManager.stopBluetoothSco();
        scoConnection = ScoConnection.DISCONNECTED;
      }
    }
  }

  private void handleBluetoothStateChange() {
    if (!destroyed.get()) {
      boolean isBluetoothAvailable = isBluetoothAvailable();

      if (!isBluetoothAvailable) {
        setWantsConnection(false);
      }

      if (listener != null)  {
        listener.onBluetoothStateChanged(isBluetoothAvailable);
      }
    }
  }

  private boolean isBluetoothAvailable() {
    try {
      synchronized (LOCK) {
        AudioManager audioManager = ServiceUtil.getAudioManager(context);

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return false;
        if (!audioManager.isBluetoothScoAvailableOffCall())            return false;

        return bluetoothHeadset != null && !bluetoothHeadset.getConnectedDevices().isEmpty();
      }
    } catch (Exception e) {
      Log.w(TAG, e);
      return false;
    }
  }

  private String getScoChangeIntent() {
    return AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED;
  }


  private void requestHeadsetProxyProfile() {
    this.bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
      @Override
      public void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (destroyed.get()) {
          Log.w(TAG, "Got bluetooth profile event after the service was destroyed. Ignoring.");
          return;
        }

        if (profile == BluetoothProfile.HEADSET) {
          synchronized (LOCK) {
            bluetoothHeadset = (BluetoothHeadset) proxy;
          }

          Intent sticky = context.registerReceiver(null, new IntentFilter(getScoChangeIntent()));
          bluetoothScoReceiver.onReceive(context, sticky);

          synchronized (LOCK) {
            if (wantsConnection && isBluetoothAvailable() && scoConnection == ScoConnection.DISCONNECTED) {
              AudioManager audioManager = ServiceUtil.getAudioManager(context);
              audioManager.startBluetoothSco();
              scoConnection = ScoConnection.IN_PROGRESS;
            }
          }

          handleBluetoothStateChange();
        }
      }

      @Override
      public void onServiceDisconnected(int profile) {
        Log.i(TAG, "onServiceDisconnected");
        if (profile == BluetoothProfile.HEADSET) {
          bluetoothHeadset = null;
          handleBluetoothStateChange();
        }
      }
    }, BluetoothProfile.HEADSET);
  }

  private class BluetoothScoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null) return;
      Log.i(TAG, "onReceive");

      synchronized (LOCK) {
        if (getScoChangeIntent().equals(intent.getAction())) {
          int status = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);

          if (status == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            if (bluetoothHeadset != null) {
              List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();

              for (BluetoothDevice device : devices) {
                if (bluetoothHeadset.isAudioConnected(device)) {
                  scoConnection = ScoConnection.CONNECTED;
                  scoConnectionAttempts = 0;

                  if (wantsConnection) {
                    AudioManager audioManager = ServiceUtil.getAudioManager(context);
                    audioManager.setBluetoothScoOn(true);
                  }
                }
              }
            }
          } else if (status == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
            setWantsConnection(false);
          }
        }
      }

      handleBluetoothStateChange();
    }
  }

  private class BluetoothConnectionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      Log.i(TAG, "onReceive");
      if (intent.getAction().equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
        int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
        if (state == BluetoothHeadset.STATE_CONNECTED) {
          scoConnectionAttempts = 0;
        }
      }
      handleBluetoothStateChange();
    }
  }

  public interface BluetoothStateListener {
    void onBluetoothStateChanged(boolean isAvailable);
  }

}
