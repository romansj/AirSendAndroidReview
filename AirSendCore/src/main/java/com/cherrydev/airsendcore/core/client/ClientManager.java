package com.cherrydev.airsendcore.core.client;


import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import com.cherrydev.airsendcore.core.ClientMessage;
import com.cherrydev.airsendcore.core.MessageType;
import com.cherrydev.airsendcore.core.OwnerProperties;
import com.cherrydev.airsendcore.core.Status;
import com.cherrydev.airsendcore.utils.ObservableSubject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.reactivex.rxjava3.subjects.PublishSubject;
import timber.log.Timber;

@SuppressLint("BinaryOperationInTimber")
public class ClientManager {


    private int MAX_RETRIES = 3;


    private IClientHandler clientHandler;
    private ISentMessageHandler messageHandler;
    private static ClientManager INSTANCE;
    private final Map<String, ClientThread> threadInfoList = new HashMap<>();
    private OwnerProperties ownerProperties;

    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private SSLContext sslContext;

    public static ClientManager getInstance() {
        if (INSTANCE == null) INSTANCE = new ClientManager();
        return INSTANCE;
    }

    private ClientManager() {
        // prevent init except getInstance()
    }


    //could update message right here and send it to a handler that implements an interface, just like clientHandler - messageStatusHandler
    private void sendMessage(ClientMessage message) {
        executor.submit(() -> {
            for (int i = 0; i < message.getMessage().length(); i++) {
                var ascii = message.getMessage().charAt(i);
                System.out.println("ascii " + ascii);
            }

            if (messageHandler != null) {
                if (message.getIdentifier() == -1) {
                    long identifier = messageHandler.addSentMessage(message);
                    message.setIdentifier(identifier);
                }
            }


            ClientThread runningClient = getRunningClient(message);
            PublishSubject<ClientResult> observableClientOut = notifyClient(runningClient, message);


            observableClientOut.take(1).timeout(10, TimeUnit.SECONDS).subscribe(clientResult -> {
                Timber.d("client says " + clientResult.toString());

                if (clientResult.isClientRunning()) {
                    if (messageHandler != null) messageHandler.updateMessageStatus(message, clientResult);
                    if (clientHandler != null) clientHandler.updateClient(message, clientResult.getTextResponse());

                } else {
                    threadInfoList.remove(message.getIP()); //can't reuse Thread class

                    // there was an error, let's try again (maybe connection reset or network issue), but only if we are trying to message/connect
                    retryOrFail(message, clientResult);
                }


            }, throwable -> {
                //throwable only when timeout
                // retryOrFail(message, new ClientResult(false,throwable));

                 throwable.printStackTrace();
                 if (messageHandler != null) messageHandler.updateMessageStatus(message, throwable);
                 if (clientHandler != null) clientHandler.updateClient(message.getIP(), Status.NOT_RUNNING, null);

                 threadInfoList.remove(runningClient.getIP()); //can't reuse Thread class
            });


        });
    }

    private void retryOrFail(ClientMessage message, ClientResult clientResult) {
        if (clientResult.getThrowable() == null || message.isKill()) {
            if (messageHandler != null) messageHandler.updateMessageStatus(message, clientResult);
            if (clientHandler != null) clientHandler.updateClient(message.getIP(), Status.NOT_RUNNING, clientResult.getTextResponse());
            return;
        }

        int retryCount = message.getRetryCount();
        if (retryCount < MAX_RETRIES) {
            int newRetryCount = retryCount + 1;
            message.setRetryCount(newRetryCount);
            sendMessage(message);

            Timber.d("sent retry, result was: " + clientResult + ", retryCount: " + newRetryCount);

        } else {
            if (messageHandler != null) messageHandler.updateMessageStatus(message, clientResult);
            if (clientHandler != null) clientHandler.updateClient(message.getIP(), Status.NOT_RUNNING, clientResult.getTextResponse());
            Timber.d("stopped trying, result was: " + clientResult + ", retryCount: " + retryCount);
        }
    }


    public void messageClient(String ip, int port, String text) {
        sendMessage(new ClientMessage(ip, port, text));
    }


    @NonNull
    private ClientThread getRunningClient(ClientMessage message) {
        ClientThread runningClient = clientIsRunning(message.getIP()); //check if a client with IP (specified in MyMessage message) is alive.
        if (runningClient == null) { //start client because we did not find a client with this IP. If message is to kill and no client is running, then we dont need to do anything
            Timber.d("sendMessage called, client not running");

            runningClient = getClientThread(message);
        }
        return runningClient;
    }


    @NonNull
    private ClientThread getClientThread(ClientMessage message) {
        ObservableSubject<ClientMessage> observableMessage = new ObservableSubject<>();
        PublishSubject<ClientResult> observableMessageOut = PublishSubject.create();
        ClientThread clientThread = new ClientThread(message.getIP(), message.getPort(), observableMessage, observableMessageOut, sslContext);
        clientThread.start();


        threadInfoList.put(clientThread.getIP(), clientThread);
        if (clientHandler != null) clientHandler.addClient(clientThread.getIP(), clientThread.getPort());


        return clientThread;
    }


    private ClientThread clientIsRunning(String messageIP) {
        for (Map.Entry<String, ClientThread> entry : threadInfoList.entrySet()) {
            Timber.d("Key = " + entry.getKey() + ", Value = " + entry.getValue().getId());
            if (messageIP.equals(entry.getKey()) && entry.getValue().isClientRunning())
                return entry.getValue();
        }

        return null;
    }


    private PublishSubject<ClientResult> notifyClient(ClientThread runningClient, ClientMessage message) {
        if (runningClient == null) return null;

        runningClient.sendMessage(message);
        return runningClient.getClientObservableOut();
    }


    public void connect(String ip, int port) {
        sendMessage(new ClientMessage(ip, port, ownerProperties.getOwnerPropertiesString(), MessageType.CONNECT));
    }


    public void disconnect(String ip, int port) {
        disconnect(ip, port, true);
    }


    public void disconnect(String ip, int port, boolean awaitResponse) {
        var clientMessage = new ClientMessage(ip, port, ownerProperties.getOwnerPropertiesString(), MessageType.DISCONNECT);
        clientMessage.setAwaitResponse(awaitResponse);
        sendMessage(clientMessage);
    }


    public void disconnectAll() {
        List<Pair<String, Integer>> listToUpdate = new ArrayList<>();
        threadInfoList.forEach((s, clientThread) -> listToUpdate.add(new Pair<>(s, clientThread.getPort())));
        listToUpdate.forEach((item) -> disconnect(item.first, item.second, false));
    }


    public void toggleConnection(String ip, int port) {
        ClientThread clientThread = clientIsRunning(ip);
        if (clientThread != null) {
            disconnect(ip, port);
        } else {
            connect(ip, port);
        }
    }

    public void deleteClient(String ip, int port) {
        disconnect(ip, port, false);
        if (clientHandler != null) clientHandler.removeClient(ip);
    }

    public void messageConnectedClients(String text) {
        threadInfoList.forEach((s, clientThread) -> {
            messageClient(s, clientThread.getPort(), text);
        });
    }

    public void connectToList(List<Pair<String, Integer>> list) {
        list.forEach(pair -> connect(pair.first, pair.second));
    }

    public void messageClients(List<Pair<String, Integer>> list, String message) {
        list.forEach(pair -> messageClient(pair.first, pair.second, message));
    }


    public void setClientHandler(IClientHandler clientHandler) {
        this.clientHandler = clientHandler;
    }

    public void setMessageHandler(ISentMessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    public void setMaxRetries(int maxRetries) {
        MAX_RETRIES = maxRetries;
    }

    public int getMaxRetries() {
        return MAX_RETRIES;
    }

    public void setOwnerProperties(OwnerProperties ownerProperties) {
        this.ownerProperties = ownerProperties;
    }

    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }
}
