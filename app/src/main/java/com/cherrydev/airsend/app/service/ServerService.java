package com.cherrydev.airsend.app.service;

import static com.cherrydev.airsend.app.MyApplication.databaseManager;
import static com.cherrydev.airsend.app.utils.IntentAction.ACTION_OPEN_APP;
import static com.cherrydev.airsend.app.utils.IntentAction.ACTION_SHARE_CLIPBOARD;
import static com.cherrydev.airsend.app.utils.IntentAction.ACTION_STOP_SERVICE;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import com.cherrydev.airsend.BuildConfig;
import com.cherrydev.airsend.R;
import com.cherrydev.airsend.app.MyApplication;
import com.cherrydev.airsend.app.database.models.Device;
import com.cherrydev.airsend.app.database.models.UserMessage;
import com.cherrydev.airsend.app.service.notification.NotificationUtils;
import com.cherrydev.airsend.app.utils.IntentActivity;
import com.cherrydev.airsend.app.utils.NetworkUtils;
import com.cherrydev.airsend.app.utils.ServiceUtils;
import com.cherrydev.airsendcore.core.ClientMessage;
import com.cherrydev.airsendcore.core.MessageType;
import com.cherrydev.airsendcore.core.OwnerProperties;
import com.cherrydev.airsendcore.core.Status;
import com.cherrydev.airsendcore.core.server.ServerManager;
import com.cherrydev.airsendcore.core.server.ServerMessage;
import com.cherrydev.airsendcore.core.server.ServerOperation;
import com.cherrydev.airsendcore.utils.SSLUtils;
import com.cherrydev.common.ClipboardUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import timber.log.Timber;

public class ServerService extends Service {

    private static int PORT = 0; //ServerSocket will use first free port
    private static Intent serviceIntent = new Intent(MyApplication.getInstance().getApplicationContext(), ServerService.class);
    private ConnectivityManager.NetworkCallback networkCallback;

    ExecutorService executorService = Executors.newFixedThreadPool(1);


    private Disposable disposableMssg;
    private Disposable disposableEvent;


    public static void startService() {
        boolean serviceRunning = ServiceUtils.isMyServiceRunning(ServerService.class);
        if (serviceRunning) {
            Timber.d("Service already running");
            return;
        }


        Context context = MyApplication.getInstance().getApplicationContext();


        SharedPreferences sharedPref = context.getSharedPreferences(context.getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        PORT = sharedPref.getInt(context.getString(R.string.last_used_port), 0); //will be updated with available one shortly


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    public static void stopService() {
        stopServer();
        boolean serviceStopped = MyApplication.getInstance().stopService(serviceIntent);
        Timber.d("Service stopped: %s", serviceStopped);
    }


    @Override
    public void onCreate() {
        Timber.d("service::onCreate()");
        // startServer();

        initNetworkListener();
    }

    @SuppressLint("MissingPermission") // Because fake error, permission is present.
    private void initNetworkListener() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            // The default network is now: 182
            @Override
            public void onAvailable(Network network) {
                startServer();
            }

            // The application no longer has a default network. The last default network was 182
            @Override
            public void onLost(Network network) {
                stopServer();
            }

            // The default network changed capabilities: [ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&NOT_ROAMING&FOREGROUND&NOT_CONGESTED&NOT_SUSPENDED LinkUpBandwidth>=465054Kbps LinkDnBandwidth>=367148Kbps SignalStrength: -67 AdministratorUids: [] RequestorUid: -1 RequestorPackageName: null]
            // The default network changed capabilities: [ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING&FOREGROUND&NOT_CONGESTED&NOT_SUSPENDED LinkUpBandwidth>=465054Kbps LinkDnBandwidth>=367148Kbps SignalStrength: -67 AdministratorUids: [] RequestorUid: -1 RequestorPackageName: null]
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                // Log.e("TAG", "The default network changed capabilities: " + networkCapabilities);
            }

            // The default network changed link properties: {InterfaceName: wlan0 LinkAddresses: [ fe80::2c60:a2ff:fe60:803/64,192.168.1.102/24 ] DnsAddresses: [ /192.168.1.254 ] Domains: null MTU: 0 ServerAddress: /192.168.1.254 TcpBufferSizes: 524288,1048576,4194304,524288,1048576,4194304 Routes: [ fe80::/64 -> :: wlan0 mtu 0,192.168.1.0/24 -> 0.0.0.0 wlan0 mtu 0,0.0.0.0/0 -> 192.168.1.254 wlan0 mtu 0 ]}
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                // Log.e("TAG", "The default network changed link properties: " + linkProperties);
            }
        };

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }


    private void startServer() {
        boolean connected = NetworkUtils.isConnectedToNetwork(this);
        if (!connected) {
            Toast.makeText(this, getString(R.string.not_connected_to_network), Toast.LENGTH_SHORT).show();
            return;
        }


        SharedPreferences sharedPref = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        int savedPort = sharedPref.getInt(getString(R.string.last_used_port), 0);
        savedPort = Math.max(savedPort, 0);

        //todo shouldnt this be done in server ssl?
        ServerManager serverManager = ServerManager.getInstance();
        serverManager.setSslContext(SSLUtils.createSSLContext(MyApplication.getInstance().getResources().openRawResource(R.raw.cherrydev), BuildConfig.CERT_KEY.toCharArray()));
        var ownerProperties = MyApplication.getOwnerProperties(this);
        serverManager.setOwnerProperties(ownerProperties);

        if (disposableEvent != null) disposableEvent.dispose(); // service killed, but ServerManager instance still lives, thus without disposing you would add a second subscription
        if (disposableMssg != null) disposableMssg.dispose();

        disposableMssg = serverManager.startServer(savedPort).subscribeOn(Schedulers.io()).subscribe(
                serverMessage -> processServerMessage(serverMessage),
                throwable -> Timber.d("serverSSL error: " + throwable.toString())
        );
        disposableEvent = serverManager.getObservableMessageEvent().subscribeOn(Schedulers.io()).subscribe(
                serverMessage2 -> processServerEvent(serverMessage2),
                throwable -> Timber.d("serverSSL error: " + throwable.toString()));
    }

    private void processServerEvent(ServerMessage serverMessage) {
        if (!serverMessage.getOperation().equals(ServerOperation.LISTEN)) return;


        PORT = serverMessage.getPort(); //only update in case of listen because EVENT also receives callback about individual server sockets working with their single clients

        SharedPreferences sharedPref = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putInt(getString(R.string.last_used_port), PORT);
        editor.apply();
    }


    private static void stopServer() {
        ServerManager.getInstance().stopServer();
    }


    private void processServerMessage(ClientMessage message) {
        String text = message.getUserMessage();
        String ip = message.getIP();
        Timber.d("SERVER MSSG: " + ip + ", :" + text);

        MessageType type = message.getType();
        if (message.isConnectionType()) {
            boolean showConnectNotifications = getSetting(R.string.setting_show_notifications);
            if (showConnectNotifications) {
                String messageText = type == MessageType.CONNECT ? getString(R.string.connected) : getString(R.string.disconnected);
                NotificationUtils.showNotification(ip, messageText);
            }


            OwnerProperties ownerProperties = OwnerProperties.fromReceived(text);
            if (ownerProperties != null) {
                Device device = new Device(ownerProperties.getName(), ip, ownerProperties.getPort(), ownerProperties.getClientType(), message.getType() == MessageType.CONNECT ? Status.RUNNING : Status.NOT_RUNNING);
                databaseManager.addDevice(device).runInBackground().run();
            }

        } else {
            ClipboardUtils.copyToClipboard(this, text);
            boolean showMessageNotifications = getSetting(R.string.setting_show_notifications_message);
            if (showMessageNotifications) NotificationUtils.showNotification(ip, text);
        }


        executorService.submit(() -> {
            var device = databaseManager.findDeviceByIP(ip);
            UserMessage userMessage = new UserMessage(ip, device != null ? device.getPort() : message.getPort(), text, type, message.getDateTime());
            databaseManager.addMessage(userMessage).run();
        });
    }

    private boolean getSetting(int p) {
        SharedPreferences sharedPref = MyApplication.getInstance().getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        return sharedPref.getBoolean(getString(p), true);
    }


    public static int getPORT() {
        return PORT;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Timber.d("service starting");

        initServerNotification(flags);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }

    private void initServerNotification(int flags) {
        Intent notificationIntent = new Intent(this, IntentActivity.class);
        notificationIntent.setAction(ACTION_OPEN_APP.getAction());
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);


        Intent sendIntent = new Intent(this, IntentActivity.class);
        Intent stopIntent = new Intent(this, IntentActivity.class);
        sendIntent.setAction(ACTION_SHARE_CLIPBOARD.getAction());
        stopIntent.setAction(ACTION_STOP_SERVICE.getAction());

        PendingIntent pendingIntentSend = PendingIntent.getActivity(this, 0, sendIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        PendingIntent pendingIntentStop = PendingIntent.getActivity(this, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        MyAction actionSend = new MyAction(ACTION_SHARE_CLIPBOARD, R.drawable.message_outline, pendingIntentSend);
        MyAction actionStop = new MyAction(ACTION_STOP_SERVICE, R.drawable.stop, pendingIntentStop);

        Notification notification = NotificationUtils.getNotification(pendingIntent, NotificationUtils.CHANNEL_ID_ONGOING,
                getString(R.string.airsend_is_working), getString(R.string.this_notification_ensures_airsend_does_not_get_killed),
                R.drawable.broadcast, List.of(actionSend, actionStop));

        // Notification ID cannot be 0.
        //todo assumption... nasty assumption about ID (need proper wrapping around android notification = NotificationUtils)
        startForeground(NotificationUtils.ONGOING_NOTIFICATION_ID, notification);
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't provide binding, so return null
    }

    @Override
    public void onDestroy() {
        PORT = 0;
        Timber.d("service done");
        NotificationUtils.dismissNotifications();

        executorService.shutdown();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.unregisterNetworkCallback(networkCallback);
    }
}
