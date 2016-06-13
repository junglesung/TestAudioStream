package com.vernonsung.testaudiostream;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;

public class AudioGroupService extends Service implements AudioManager.OnAudioFocusChangeListener{
    public class LocalBinder extends Binder {
        AudioGroupService getService() {
            // Return this instance of service so clients can call public methods
            return AudioGroupService.this;
        }
    }

    enum PlayerState {
        INITIAL, PREPARED, PLAYING
    }

    public static final String ACTION_PLAY = "com.vernonsung.testaudiostream.action.play";
    public static final String ACTION_STOP = "com.vernonsung.testaudiostream.action.stop";
    public static final String INTENT_EXTRA_IP = "com.vernonsung.testaudiostream.IP";
    public static final String INTENT_EXTRA_PORT = "com.vernonsung.testaudiostream.PORT";
    private static final String LOG_TAG = "testtest";
    private static final String WIFI_LOCK = "wifiLock";
    private static final String WAKE_LOCK = "wakeLock";
    private final IBinder mBinder = new LocalBinder();
    private AudioStream stream;
    private AudioGroup group;
    private InetSocketAddress remoteSocket;
    private int originalAudioMode = AudioManager.MODE_INVALID;
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;
    private PlayerState currentState = PlayerState.INITIAL;

    public AudioGroupService() {
    }

    @Override
    public void onCreate() {
        // Get local address
        InetAddress localIp = getLocalIpAddress();
        if (localIp == null) {
            Log.d(LOG_TAG, "Local IP not found");
            stopSelf();
            return;
        }
        Log.d(LOG_TAG, "Local IP " + localIp.getHostAddress());

        // Initial stream
        try {
            stream = new AudioStream(localIp);
        } catch (SocketException e) {
            Log.e(LOG_TAG, "Initial AudioStream failed because " + e.getMessage());
            e.printStackTrace();
            return;
        }
        Log.d(LOG_TAG, "Local port " + String.valueOf(stream.getLocalPort()));
        stream.setCodec(AudioCodec.AMR);
        stream.setMode(RtpStream.MODE_NORMAL);

        // Initial group
        group = new AudioGroup();
        group.setMode(AudioGroup.MODE_ECHO_SUPPRESSION);

        // Change state
        currentState = PlayerState.PREPARED;
        Log.d(LOG_TAG, "Service state -> PREPARED");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        switch (intent.getAction()) {
            case ACTION_PLAY:
                onActionPlay(intent);
                break;
            case ACTION_STOP:
                onActionStop();
                break;
            default:
                Log.e(LOG_TAG, "Unknown action " + intent.getAction());
                break;
        }
        // Don't restart service with last intent if it's killed by the system
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (abandonFocus()) {
            Log.d(LOG_TAG, "Abandon audio focus successfully");
        } else {
            Log.d(LOG_TAG, "Abandon audio focus failed");
        }
        restoreAudioMode();
        releaseLock();
    }

    // Be aware of audio focus change
    @Override
    public void onAudioFocusChange(int focusChange) {
        // Do something based on focus change...
        Log.d(LOG_TAG, "Audio focus change is called");
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                group.setMode(AudioGroup.MODE_ECHO_SUPPRESSION);
                Log.d(LOG_TAG, "Audio focus change -> AUDIOFOCUS_GAIN");
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time
                group.setMode(AudioGroup.MODE_ON_HOLD);
                Log.d(LOG_TAG, "Audio focus change -> AUDIOFOCUS_LOSS");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time
                group.setMode(AudioGroup.MODE_ON_HOLD);
                Log.d(LOG_TAG, "Audio focus change -> AUDIOFOCUS_LOSS_TRANSIENT");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                // Do nothing
                Log.d(LOG_TAG, "Audio focus change -> AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                break;
        }
    }

    // After receiving an intent with a "PLAY" action
    private void onActionPlay(Intent intent) {
        if (currentState == PlayerState.INITIAL) {
            Log.e(LOG_TAG, "Initial failed");
            stopSelf();
            return;
        }

        // Get remote socket from the intent
        String ip = intent.getStringExtra(INTENT_EXTRA_IP);
        int port = intent.getIntExtra(INTENT_EXTRA_PORT, 0);
        Log.w(LOG_TAG, "Remote " + ip + ":" + String.valueOf(port));
        if (ip == null || ip.isEmpty() || port == 0) {
            return;
        }
        remoteSocket = new InetSocketAddress(ip, port);

        if (currentState == PlayerState.PREPARED) {
            startPlayAudio();
        } else if (currentState == PlayerState.PLAYING) {
            changeTarget();
        }
    }

    // After receiving an intent with a "STOP" action
    private void onActionStop() {
        stopPlayAudio();
        stopSelf();
    }

    // PREPARED -> PLAYING
    private void startPlayAudio() {
        try {
            // AudioGroup needs this.
            setupAudioMode();

            // Acquire lock to avoid power saving mode
            acquireLock();

            // Turn into a foreground service. Provide a running notification
            turnIntoForeground();

            // Acquire audio focus to avoid influence from other APPs
            acquireAudioFocus();

            // Start sending voice
            stream.associate(remoteSocket.getAddress(), remoteSocket.getPort());
            stream.join(group);
            Log.d(LOG_TAG, stream.getLocalAddress().getHostAddress() + ":" + stream.getLocalPort() + " -> " +
                           stream.getRemoteAddress().getHostAddress() + ":" + stream.getRemotePort());

            AudioManager m = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
            int currentVolume = m.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVolume = m.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            Log.d(LOG_TAG, "Volume " + String.valueOf(currentVolume) + "/" + String.valueOf(maxVolume));
            if (m.isMicrophoneMute()) {
                Log.d(LOG_TAG, "Microphone is mute");
            } else {
                Log.d(LOG_TAG, "Microphone is not mute");
            }
            if (m.isSpeakerphoneOn()) {
                Log.d(LOG_TAG, "Speaker is ON");
            } else {
                Log.d(LOG_TAG, "Speaker is not ON");
            }
            if (m.isMusicActive()) {
                Log.d(LOG_TAG, "Music is active");
            } else {
                Log.d(LOG_TAG, "Music is not active");
            }
            int groupMode = group.getMode();
            switch (groupMode) {
                case AudioGroup.MODE_ECHO_SUPPRESSION:
                    Log.d(LOG_TAG, "MODE_ECHO_SUPPRESSION");
                    break;
                case AudioGroup.MODE_MUTED:
                    Log.d(LOG_TAG, "MODE_MUTED");
                    break;
                case AudioGroup.MODE_NORMAL:
                    Log.d(LOG_TAG, "MODE_NORMAL");
                    break;
                case AudioGroup.MODE_ON_HOLD:
                    Log.d(LOG_TAG, "MODE_ON_HOLD");
                    break;
            }
            int mode = m.getMode();
            switch (mode) {
                case AudioManager.MODE_IN_CALL:
                    Log.d(LOG_TAG, "MODE_IN_CALL");
                    break;
                case AudioManager.MODE_IN_COMMUNICATION:
                    Log.d(LOG_TAG, "MODE_IN_COMMUNICATION");
                    break;
                case AudioManager.MODE_NORMAL:
                    Log.d(LOG_TAG, "MODE_NORMAL");
                    break;
                case AudioManager.MODE_RINGTONE:
                    Log.d(LOG_TAG, "MODE_RINGTONE");
                    break;
            }
            // Change state
            currentState = PlayerState.PLAYING;
            Log.d(LOG_TAG, "Service state -> PLAYING");
        } catch (Exception e) {
            Log.e(LOG_TAG, e.toString());
            stopSelf();
        }
    }

    // While PLAYING
    private void stopPlayAudio() {
        if (currentState == PlayerState.PLAYING) {
            stream.join(null);
            remoteSocket = null;
            Log.d(LOG_TAG, "Audio stopped");
        }
    }

    // While PLAYING
    private void changeTarget() {
        if (currentState == PlayerState.PLAYING) {
            stream.join(null);
            stream.associate(remoteSocket.getAddress(), remoteSocket.getPort());
            stream.join(group);
            Log.d(LOG_TAG, "Target changed -> " + remoteSocket.getAddress().getHostAddress() + ":" + String.valueOf(remoteSocket.getPort()));
        }
    }

    private InetAddress getLocalIpAddress() {
        // byte ip[]=null;
        try {
            ArrayList<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface i : interfaces) {
                ArrayList<InetAddress> addresses = Collections.list(i.getInetAddresses());
                for (InetAddress a : addresses) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address && a.getHostAddress().startsWith("192")) {
                        Log.d(LOG_TAG, a.getHostAddress() + " is chosen as local IP");
                        return a;
                    } else {
                        Log.d(LOG_TAG, "IP " + a.getHostAddress() + " is found but not what we want");
                    }
                }
            }
        } catch (SocketException ex) {
            Log.d("SocketException ", ex.toString());
        }
        Log.e(LOG_TAG, "No useful IP");
        return null;
    }

    private void setupAudioMode() {
        AudioManager m = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        originalAudioMode = m.getMode();
        m.setMode(AudioManager.MODE_IN_COMMUNICATION);
        Log.d(LOG_TAG, "Set audio mode MODE_IN_COMMUNICATION");
    }

    private void restoreAudioMode() {
        AudioManager m = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if (originalAudioMode != AudioManager.MODE_INVALID) {
            m.setMode(originalAudioMode);
            Log.d(LOG_TAG, "Restore audio mode " + originalAudioMode);
        }
    }

    // Acquire lock to avoid power saving mode
    private void acquireLock() {
        // To ensure that the CPU continues running
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK);
        wakeLock.acquire();
        // To ensure Wi-Fi is ON
        wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, WIFI_LOCK);
        wifiLock.acquire();
    }

    // Release lock to save power
    private void releaseLock() {
        if (wifiLock != null) {
            wifiLock.release();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
    }

    // Turn into a foreground service. Provide a running notification
    private void turnIntoForeground() {
        int NOTIFICATION_ID = 1;
        // assign the song name to songName
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                new Intent(getApplicationContext(), RtpConnectActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this)
                .setContentTitle(getString(R.string.test_audio))
                .setContentText(getString(R.string.voice_is_sending))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        startForeground(NOTIFICATION_ID, notification);
    }

    // Turn info background
    private void turnIntoBackground() {
        stopForeground(true);
    }

    // Acquire audio focus
    private void acquireAudioFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // could not get audio focus.
            Log.d(LOG_TAG, "Request audio focus failed");
        }
        Log.d(LOG_TAG, "Request audio focus successfully");
    }

    // Release audio focus
    private boolean abandonFocus() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.abandonAudioFocus(this);
    }

    // Get stream local IP port
    public InetSocketAddress getLocalIpPort() {
        if (stream == null) {
            Log.d(LOG_TAG, "stream is not initialized");
            return null;
        }
        return new InetSocketAddress(stream.getLocalAddress(), stream.getLocalPort());
    }

    // Get stream remote IP port
    public InetSocketAddress getRemoteIpPort() {
        if (currentState != PlayerState.PLAYING) {
            Log.d(LOG_TAG, "Remote socket is not set");
            return null;
        }
        return new InetSocketAddress(stream.getRemoteAddress(), stream.getRemotePort());
    }
}
