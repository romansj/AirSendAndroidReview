package com.cherrydev.airsend.core;


import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.cherrydev.airsend.app.service.ClientManagerOwnerProperties;
import com.cherrydev.airsend.app.utils.NetworkUtils;
import com.cherrydev.airsend.core.client.ClientManager;
import com.cherrydev.airsend.core.server.ServerManager;
import com.cherrydev.airsend.core.server.ServerMessage;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */

@RunWith(AndroidJUnit4.class)
public class IntegrationClientServer {
    static int port;
    static String ip;

    @BeforeClass
    public static void setupClass() {
        // this allows us to execute code on the calling thread so the test case would not exit before we are done
        RxJavaPlugins.setIoSchedulerHandler(__ -> Schedulers.trampoline());


        port = NetworkUtils.nextFreePort(30000, 50000);
        ip = NetworkUtils.getIPAddress(true);
    }

    // if we don't pass here, then test did not instantiate correctly
    @Test
    public void useAppContext() {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("com.cherrydev.airsend", appContext.getPackageName());
    }


    // loopback connection accepted
    @Test
    public void serverAcceptsClientConnection() {

        List<String> expectedOutput = List.of(port + ",T,unit", "Hello world!");


        ServerManager serverManager = ServerManager.getInstance();
        Observable<ClientMessage> messageObservable = serverManager.startServer(port);


        ServerMessage serverMessage = serverManager.getObservableMessageEvent().blockingFirst();
        port = serverMessage.getPort();


        ClientManager clientManager = ClientManager.getInstance();
        clientManager.setOwnerProperties(new ClientManagerOwnerProperties(port, "unit", "T"));
        // end setup


        // test all messages received
        List<String> outputList = new ArrayList<>();

        clientManager.connect(ip, port);
        String responseToConnect = messageObservable.blockingFirst().getUserMessage().trim();
        outputList.add(responseToConnect);

        clientManager.messageClient(ip, port, "Hello world!");
        String responseToHello = messageObservable.blockingNext().iterator().next().getUserMessage().trim();
        outputList.add(responseToHello);


        clientManager.disconnectAll();
        serverManager.stopServer();


        assertThat(outputList).isEqualTo(expectedOutput);
    }


    @Test
    public void runningServerDoesNotRunTwice() {
        ServerManager serverManager = ServerManager.getInstance();

        Observable<ClientMessage> messageObservableStart = serverManager.startServer(port);
        Observable<ClientMessage> messageObservableStartAgain = serverManager.startServer(port);

        // start a started server should just return its observable
        assertThat(messageObservableStartAgain).isSameInstanceAs(messageObservableStart);
    }

    @Test
    public void stoppedServerCleansUp() {
        ServerManager serverManager = ServerManager.getInstance();

        Observable<ClientMessage> messageObservableStart = serverManager.startServer(port);
        serverManager.stopServer();
        Observable<ClientMessage> messageObservableStartAgain = serverManager.startServer(port);

        // two diff instances unless cleanup was skipped
        assertThat(messageObservableStartAgain).isNotSameInstanceAs(messageObservableStart);
    }


    @Test
    public void stoppedServerDoesNotAcceptConnections() {
        // accept connection,
        // close all connections,
        // stop running,
        // make new connection request,
        // should not be accepted,

        var connectionString = port + ",T1,unit1";
        List<String> expectedOutput = List.of(connectionString, connectionString, "Stopped", "No more elements");
        List<String> outputList = new ArrayList<>();


        ServerManager serverManager = ServerManager.getInstance();
        Observable<ClientMessage> serverObservable = serverManager.startServer(port);

        ClientManager clientManager = ClientManager.getInstance();
        clientManager.setOwnerProperties(new ClientManagerOwnerProperties(port, "unit1", "T1"));


        clientManager.connect(ip, port);
        String responseToConnect = serverObservable.blockingFirst().getUserMessage().trim();
        outputList.add(responseToConnect);

        clientManager.disconnect(ip, port);
        String responseToDisconnect = serverObservable.blockingNext().iterator().next().getUserMessage().trim();
        outputList.add(responseToDisconnect);


        serverManager.stopServer();
        var responseToStop = serverManager.getObservableMessageEvent().blockingNext().iterator().next().getOperation();
        outputList.add(responseToStop.toString());


        clientManager.setOwnerProperties(new ClientManagerOwnerProperties(port, "unit2", "T2"));
        clientManager.connect(ip, port);
        try {
            String responseToConnectAfterStop = serverObservable.take(1).blockingNext().iterator().next().getUserMessage().trim();
            outputList.add(responseToConnectAfterStop);
        } catch (NoSuchElementException e) {
            outputList.add(e.getMessage());
        }


        assertThat(outputList).isEqualTo(expectedOutput);
    }

    @Test
    public void canConnectAfterDisconnect() {

        var connectionString = port + ",T2,unit2";
        List<String> expectedOutput = List.of(connectionString, "Stopped", connectionString);
        List<String> outputList = new ArrayList<>();


        ServerManager serverManager = ServerManager.getInstance();
        Observable<ClientMessage> serverObservable = serverManager.startServer(port);
        var serverEvent = serverManager.getObservableMessageEvent();

        ClientManager clientManager = ClientManager.getInstance();
        clientManager.setOwnerProperties(new ClientManagerOwnerProperties(port, "unit2", "T2"));


        clientManager.connect(ip, port);
        String responseToConnect = serverObservable.blockingFirst().getUserMessage().trim();
        outputList.add(responseToConnect);

        clientManager.disconnect(ip, port);
        var responseToDisconnect = serverEvent.blockingNext().iterator().next().getOperation().toString();
        outputList.add(responseToDisconnect);

        clientManager.connect(ip, port);
        String responseToConnectAgain = serverObservable.blockingNext().iterator().next().getUserMessage().trim();
        outputList.add(responseToConnectAgain);


        assertThat(outputList).isEqualTo(expectedOutput);
    }
}
