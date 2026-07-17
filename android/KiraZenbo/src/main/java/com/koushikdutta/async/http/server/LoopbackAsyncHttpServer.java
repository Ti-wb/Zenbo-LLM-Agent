package com.koushikdutta.async.http.server;

import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncServerSocket;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** AndroidAsync adapter that prevents the embedded renderer API from binding to LAN interfaces. */
public final class LoopbackAsyncHttpServer extends AsyncHttpServer {
    public AsyncServerSocket listenLoopback(int port) throws UnknownHostException {
        return AsyncServer.getDefault().listen(
                InetAddress.getByName("127.0.0.1"),
                port,
                mListenCallback
        );
    }
}
