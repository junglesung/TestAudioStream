package com.vernonsung.testaudiostream;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;

public class RtpConnectActivity extends AppCompatActivity {
    private static final String LOG_TAG = "testtest";
    private static final int PERMISSIONS_REQUEST_START_AUDIO_SERVICE = 100;
    private InetSocketAddress localSocket;
    private InetSocketAddress remoteSocket;
    private AudioGroupService mService;
    private ServiceConnection mConnection;

    // UI
    private TextView textViewLocalIp;
    private TextView textViewLocalPort;
    private EditText editTextRemoteIp;
    private EditText editTextRemotePort;
    private Button   buttonPlay;
    private Button   buttonStop;
    private Button   buttonRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rtp_connect);

        // Get UI components
        textViewLocalIp = (TextView) findViewById(R.id.textViewLocalIp);
        textViewLocalPort = (TextView) findViewById(R.id.textViewLocalPort);
        editTextRemoteIp = (EditText) findViewById(R.id.editTextRemoteIp);
        editTextRemotePort = (EditText) findViewById(R.id.editTextRemotePort);
        buttonPlay = (Button) findViewById(R.id.buttonPlay);
        buttonStop = (Button) findViewById(R.id.buttonStop);
        buttonRefresh = (Button) findViewById(R.id.buttonRefresh);

        // Set UI action
        buttonPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionToStartAudioService();
            }
        });
        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAudioService();
            }
        });
        buttonRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLocalSocket();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindAudioService();
    }

    @Override
    protected void onStop() {
        unbindAudioService();
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_START_AUDIO_SERVICE:
                permissionHandlerToStartAudioService(grantResults);
                break;
        }
    }

    private void permissionHandlerToStartAudioService(int[] grantResults) {
        // If request is cancelled, the result arrays are empty.
        if (grantResults.length == 0) {
            return;
        }
        // Check whether every permission is granted
        for (int i : grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                return;
            }
        }
        // Finally all permission are granted
        startAudioService();
    }

    private void checkPermissionToStartAudioService() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            startAudioService();
            return;
        }
        // PackageManager.PERMISSION_DENIED
        ActivityCompat.requestPermissions(this,
                                          new String[]{Manifest.permission.RECORD_AUDIO},
                                          PERMISSIONS_REQUEST_START_AUDIO_SERVICE);
    }

    private void startAudioService() {
        Intent intent = new Intent(this, AudioGroupService.class);
        intent.setAction(AudioGroupService.ACTION_PLAY);
        // Get remote IP and port from UI and put them into the intent
        try {
            String ip = editTextRemoteIp.getText().toString();
            int port = Integer.parseInt(editTextRemotePort.getText().toString());
            if (ip.isEmpty()) {
                Log.e(LOG_TAG, "No remote IP");
                Toast.makeText(this, R.string.please_give_the_remote_device_ip, Toast.LENGTH_LONG).show();
                editTextRemoteIp.requestFocus();
                return;
            }
            if (port < 1 || port > 65534) {
                Log.e(LOG_TAG, "Wrong remote port " + editTextRemotePort.getText().toString());
                Toast.makeText(this, R.string.please_give_the_right_port, Toast.LENGTH_LONG).show();
                editTextRemotePort.requestFocus();
            }
            remoteSocket = new InetSocketAddress(ip, port);
            intent.putExtra(AudioGroupService.INTENT_EXTRA_IP, ip);
            intent.putExtra(AudioGroupService.INTENT_EXTRA_PORT, port);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Wrong remote port " + editTextRemotePort.getText().toString());
            Toast.makeText(this, R.string.please_give_the_right_port, Toast.LENGTH_LONG).show();
            editTextRemotePort.requestFocus();
            return;
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
            return;
        }
        // Make service running until manually stop it
        startService(intent);
    }

    private void stopAudioService() {
        Intent intent = new Intent(this, AudioGroupService.class);
        intent.setAction(AudioGroupService.ACTION_STOP);
        startService(intent);
    }

    // Bind AudioGroupService when activity starts
    private void bindAudioService() {
        try {
            Intent intent = new Intent(this, AudioGroupService.class);
            mConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName className,
                                               IBinder service) {
                    // We've bound to LocalService, cast the IBinder and get LocalService instance
                    AudioGroupService.LocalBinder binder = (AudioGroupService.LocalBinder) service;
                    mService = binder.getService();
                    showLocalSocket();
                    showRemoteSocket();
                    Log.d(LOG_TAG, "Audio service is connected. Update local IP port");
                }

                @Override
                public void onServiceDisconnected(ComponentName arg0) {
                    // Do nothing
                    Log.d(LOG_TAG, "Audio service is disconnected accidentally");
                }
            };
            if (!bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.d(LOG_TAG, "Bind service failed");
            } else {
                Log.d(LOG_TAG, "Bind service successfully");
            }
        } catch (SecurityException e) {
            // No permission to bind the service
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
        }
    }

    // Unbind AudioGroupService when activity stops
    private void unbindAudioService() {
        unbindService(mConnection);
    }

    private void showLocalSocket() {
        // Check service is running
        if (mService == null) {
            Log.e(LOG_TAG, "service is stopped");
            return;
        }
        // Call service's public methods
        localSocket = mService.getLocalIpPort();
        if (localSocket == null) {
            Log.d(LOG_TAG, "Get local IP and port failed");
            return;
        }
        // Show localSocket IP and port in text view
        textViewLocalIp.setText(localSocket.getAddress().getHostAddress());
        textViewLocalPort.setText(String.valueOf(localSocket.getPort()));
    }

    private void showRemoteSocket() {
        // Check service is running
        if (mService == null) {
            Log.e(LOG_TAG, "service is stopped");
            return;
        }
        // Call service's public methods
        remoteSocket = mService.getRemoteIpPort();
        if (remoteSocket == null) {
            Log.d(LOG_TAG, "Get remote IP and port failed");
            return;
        }
        // Show localSocket IP and port in text view
        editTextRemoteIp.setText(remoteSocket.getAddress().getHostAddress());
        editTextRemotePort.setText(String.valueOf(remoteSocket.getPort()));
    }
}
