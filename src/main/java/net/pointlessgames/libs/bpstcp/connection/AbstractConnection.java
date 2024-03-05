package net.pointlessgames.libs.bpstcp.connection;

import net.pointlessgames.libs.bps.DeserializationContext;
import net.pointlessgames.libs.bps.IDeserializationContext;
import net.pointlessgames.libs.bps.IObjectReference;
import net.pointlessgames.libs.bps.ISerializationContext;
import net.pointlessgames.libs.bps.SerializationContext;
import net.pointlessgames.libs.bpstcp.exception.RemoteException;
import net.pointlessgames.libs.bpstcp.request.IAwaitableRequestTicket;
import net.pointlessgames.libs.bpstcp.request.IRequest;
import net.pointlessgames.libs.bpstcp.request.IRequestHandler;
import net.pointlessgames.libs.bpstcp.request.IRequestTicket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/*package-protected (for now)*/ abstract class AbstractConnection<IncomingPacketType, OutgoingPacketType> implements IConnection<IncomingPacketType, OutgoingPacketType> {
    private static final byte PACKET_TYPE__DATA = 0;
    private static final byte PACKET_TYPE__REQUEST = 1;
    private static final byte PACKET_TYPE__RESPONSE_OK = 2;
    private static final byte PACKET_TYPE__RESPONSE_EXCEPTION = 3;

    private final NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization;

    private final List<Listener<IncomingPacketType>> listeners = new ArrayList<>();

    private final Map<RequestId, PendingRequest<?>> pendingRequests = new HashMap<>();

    private RequestHandler incomingRequestHandler;

    private ConnectionThread connectionThread;

    private final List<DisconnectListener> disconnectListeners = new ArrayList<>();

    public AbstractConnection(NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization) {
        this.serialization = serialization;
    }

    protected Socket getSocket() {
        return connectionThread != null ? connectionThread.socket : null;
    }

    protected synchronized void connect(Socket socket, Runnable onConnected) throws IOException {
        if(connectionThread != null) {
            throw new IllegalStateException("Already connected!");
        }
        socket.setTcpNoDelay(true);
        this.connectionThread = new ConnectionThread(socket);
        onConnected.run();
        connectionThread.start();
    }

    @Override
    public synchronized void addListener(Executor executor, IPacketListener<IncomingPacketType> listener) {
        listeners.add(new Listener<>(executor, listener));
    }

    @Override
    public void setRequestHandler(Executor executor, IRequestHandler requestHandler) {
        this.incomingRequestHandler = new RequestHandler(executor, requestHandler);
    }

    @Override
    public synchronized void send(OutgoingPacketType packet) {
        try {
            SerializationContext serializationContext = new SerializationContext(connectionThread.outputStream, serialization.outgoingObjectSerializer);
            serializationContext.writeByte(PACKET_TYPE__DATA);
            serializationContext.write(serialization.outgoingPacketSerializer, packet);
        } catch (IOException e) {
            //TODO handle more gracefully
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized <T> IRequestTicket<T> send(IRequest<T> request, Executor executor, /*@Nullable*/ IPacketListener<T> responsePacketListener) {
        StackTraceElement[] callSiteStackTrace = null;
        if(responsePacketListener != null) {
            callSiteStackTrace = makeCallSiteStackTrace();
        }
        return sendInternal(request, executor, responsePacketListener, null, callSiteStackTrace);
    }

    @Override
    public synchronized <T> IRequestTicket<T> send(IRequest<T> request, Executor executor, IPacketListener<T> responsePacketListener, Consumer<RemoteException> onException) {
        StackTraceElement[] callSiteStackTrace = null;
        if(responsePacketListener != null) {
            callSiteStackTrace = makeCallSiteStackTrace();
        }
        return sendInternal(request, executor, responsePacketListener, onException, callSiteStackTrace);
    }

    @Override
    public <T> IAwaitableRequestTicket<T> send(IRequest<T> request, Executor executor) {
        StackTraceElement[] callSiteStackTrace = makeCallSiteStackTrace();
        class AsyncResult<T> extends FutureTask<T> {
            public AsyncResult() {
                super(() -> {
                    throw new UnsupportedOperationException();
                });
            }

            @Override
            public void set(T t) {
                super.set(t);
            }

            @Override
            public void setException(Throwable t) {
                super.setException(t);
            }
        }
        class Awaitable implements IAwaitableRequestTicket<T>, IPacketListener<T> {
            private final List<IPacketListener<T>> listeners = new ArrayList<>();
            private final List<Consumer<Throwable>> exceptionListeners = new ArrayList<>();
            private final AsyncResult<T> result = new AsyncResult<>();
            private IRequestTicket<T> ticket;

            private T getResultInternal() {
                try {
                    return result.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            }

            @Override
            public T await() {
                try {
                    return result.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if(cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    } else {
                        throw new RuntimeException(cause);
                    }
                }
            }

            @Override
            public void onResponse(IPacketListener<T> responsePacketListener, Consumer<Throwable> onException) {
                if(result.isDone()) {
                    final T value;
                    try {
                        value = getResultInternal();
                    } catch (RuntimeException e) {
                        if(onException == null) {
                            throw e;
                        }
                        onException.accept(e.getCause());
                        return;
                    }
                    responsePacketListener.onPacket(value);
                } else {
                    listeners.add(responsePacketListener);
                    if(onException != null) {
                        exceptionListeners.add(onException);
                    }
                }
            }

            @Override
            public void cancel() {
                ticket.cancel();
            }

            @Override
            public boolean isPending() {
                return ticket.isPending();
            }

            @Override
            public void onPacket(T packet) {
                List<IPacketListener<T>> harvest = null;
                synchronized (this) {
                    result.set(packet);
                    if(!listeners.isEmpty()) {
                        harvest = new ArrayList<>(listeners);
                        listeners.clear();
                    }
                    exceptionListeners.clear();
                }
                if(harvest != null) {
                    runAll(harvest, listener -> listener.onPacket(packet));
//                    RuntimeException exceptions = null;
//                    for(IPacketListener<T> listener : harvest) {
//                        try {
//                            listener.onPacket(packet);
//                        } catch (RuntimeException e) {
//                            if(exceptions == null) {
//                                exceptions = e;
//                            } else {
//                                exceptions.addSuppressed(e);
//                            }
//                        }
//                    }
//                    if(exceptions != null) {
//                        throw exceptions;
//                    }
                }
            }

            public void onRemoteException(RemoteException remoteException) {
                List<Consumer<Throwable>> harvest = null;
                synchronized (this) {
                    result.setException(remoteException);
                    if(!exceptionListeners.isEmpty()) {
                        harvest = new ArrayList<>(exceptionListeners);
                        exceptionListeners.clear();
                    }
                    listeners.clear();
                }
                if(harvest != null) {
                    runAll(harvest, onException -> onException.accept(remoteException.getCause()));
//                    RuntimeException exceptions = null;
//                    for(Consumer<Throwable> onException : harvest) {
//                        try {
//                            onException.accept(remoteException.getCause());
//                        } catch (RuntimeException e) {
//                            if(exceptions == null) {
//                                exceptions = e;
//                            } else {
//                                exceptions.addSuppressed(e);
//                            }
//                        }
//                    }
//                    if(exceptions != null) {
//                        throw exceptions;
//                    }
                }
            }
        }
        Awaitable awaitable = new Awaitable();
        awaitable.ticket = sendInternal(request, executor, awaitable, awaitable::onRemoteException, callSiteStackTrace);
        return awaitable;
    }

    private synchronized <T> IRequestTicket<T> sendInternal(IRequest<T> request, Executor executor, /*@Nullable*/ IPacketListener<T> responsePacketListener, /*@Nullable*/ Consumer<RemoteException> onException, StackTraceElement[] callSiteStackTrace) {
        try {
            SerializationContext serializationContext = new SerializationContext(connectionThread.outputStream, serialization.outgoingObjectSerializer);
            serializationContext.writeByte(PACKET_TYPE__REQUEST);
            PendingRequest<T> pendingRequest = new PendingRequest<>(RequestId.next(), request, executor, responsePacketListener, onException, callSiteStackTrace);
            if(responsePacketListener != null) {
                pendingRequests.put(pendingRequest.requestId, pendingRequest);
            }
            pendingRequest.send(serializationContext);
            return new IRequestTicket<T>() {
                @Override
                public void cancel() {
                    pendingRequests.remove(pendingRequest.requestId);
                    //TODO send cancel notification to server so it MAY cancel long-running request
                }

                @Override
                public boolean isPending() {
                    return pendingRequests.containsKey(pendingRequest.requestId);
                }
            };
        } catch (IOException e) {
            //TODO handle more gracefully
            throw new RuntimeException(e);
        }
    }

    private synchronized void sendResponseOk(RequestId requestId, Object response) {
        try {
            SerializationContext context = new SerializationContext(connectionThread.outputStream, serialization.outgoingObjectSerializer);
            context.writeByte(PACKET_TYPE__RESPONSE_OK);
            requestId.writeTo(context);
            context.writeObject(response);
        } catch (IOException e) {
            //TODO handle more gracefully
            throw new RuntimeException(e);
        }
    }

    private synchronized void sendResponseException(RequestId requestId, Exception exception) {
        try {
            SerializationContext context = new SerializationContext(connectionThread.outputStream, serialization.outgoingObjectSerializer);
            exception.printStackTrace();
            context.writeByte(PACKET_TYPE__RESPONSE_EXCEPTION);
            requestId.writeTo(context);
            new GeneralException(exception).writeTo(context);
        } catch (IOException e) {
            //TODO handle more gracefully
            throw new RuntimeException(e);
        }
    }

    private synchronized void handlePacket(IncomingPacketType packet) {
        for(Listener<IncomingPacketType> listener : listeners) {
            listener.onPacket(packet);
        }
    }

    private synchronized void handleIncomingRequest(RequestId requestId, IObjectReference<IRequest> requestReference) {
        if(incomingRequestHandler != null) {
            final RequestHandler handler = incomingRequestHandler;
            requestReference.get(request -> {
                if(handler.executor != null) {
                    handler.executor.execute(() -> {
                        //TODO check if cancelled from client!
                        try {
                            handler.requestHandler.respond(new IRequestHandler.IRequestResolver() {
                                @Override
                                public <T> IRequestHandler.ResponseHandling resolve(IRequest<T> request, T response) {
                                    sendResponseOk(requestId, response);
                                    return IRequestHandler.ResponseHandling.HANDLED;
                                }
                            }, request);
                        } catch (Exception e) {
                            sendResponseException(requestId, e);
                        }
                    });
                } else {
                    try {
                        handler.requestHandler.respond(new IRequestHandler.IRequestResolver() {
                            @Override
                            public <T> IRequestHandler.ResponseHandling resolve(IRequest<T> request, T response) {
                                sendResponseOk(requestId, response);
                                return IRequestHandler.ResponseHandling.HANDLED;
                            }
                        }, request);
                    } catch (Exception e) {
                        sendResponseException(requestId, e);
                    }
                }
            });
        } else {
            sendResponseException(requestId, new UnsupportedOperationException("Can not handle requests. No request handler set."));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleIncomingResponse(RequestId requestId, IObjectReference<Object> responseReference) {
        PendingRequest request = pendingRequests.remove(requestId);
        if(request != null) {
            responseReference.get(request::onResponse);
        }
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void handleIncomingResponseGeneralException(RequestId requestId, GeneralException generalException) {
        PendingRequest request = pendingRequests.remove(requestId);
        if(request != null) {
            request.onGeneralException(generalException);
        }
    }

    @Override
    public void addDisconnectListener(Executor executor, Runnable onDisconnected) {
        disconnectListeners.add(new DisconnectListener(executor, onDisconnected));
    }

    @Override
    public void close() throws IOException {
        connectionThread.socket.close();
        connectionThread.interrupt();
        try {
            connectionThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.pendingRequests.clear();
        this.listeners.clear();
        runAll(disconnectListeners, DisconnectListener::onDisconnect);
        disconnectListeners.clear();
    }

    private static <T> void runAll(Iterable<T> iterable, Consumer<T> action) {
        RuntimeException exceptions = null;
        for(T item : iterable) {
            try {
                action.accept(item);
            } catch (RuntimeException e) {
                if(exceptions == null) {
                    exceptions = e;
                } else {
                    exceptions.addSuppressed(e);
                }
            }
        }
        if(exceptions != null) {
            throw exceptions;
        }
    }

    private static class Listener<T> {
        private final Executor executor;
        private final IPacketListener<T> listener;

        public Listener(Executor executor, IPacketListener<T> listener) {
            this.executor = executor;
            this.listener = listener;
        }

        public void onPacket(T packet) {
            if(executor != null) {
                executor.execute(() -> {
                    listener.onPacket(packet);
                });
            } else {
                listener.onPacket(packet);
            }
        }
    }

    private static class DisconnectListener {
        private final Executor executor;
        private final Runnable listener;

        public DisconnectListener(Executor executor, Runnable listener) {
            this.executor = executor;
            this.listener = listener;
        }

        public void onDisconnect() {
            if(executor != null) {
                executor.execute(listener);
            } else {
                listener.run();
            }
        }
    }

    private class ConnectionThread extends Thread {
        private final Socket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ConnectionThread(Socket socket) throws IOException {
            this.socket = socket;
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        }

        @Override
        public void run() {
            while(!Thread.interrupted()) {
                DeserializationContext context = new DeserializationContext(inputStream, serialization.incomingObjectDeserializer);
                try {
                    byte packetType = context.readByte();
                    if(packetType == PACKET_TYPE__DATA) {
                        IncomingPacketType packet = context.read(serialization.incomingPacketDeserializer);
                        handlePacket(packet);
                    } else if(packetType == PACKET_TYPE__REQUEST) {
                        RequestId requestId = RequestId.readFrom(context);
                        IObjectReference<IRequest> request = context.readObject(IRequest.class);
                        handleIncomingRequest(requestId, request);
                    } else if(packetType == PACKET_TYPE__RESPONSE_OK) {
                        RequestId requestId = RequestId.readFrom(context);
                        IObjectReference<Object> responseReference = context.readObject(Object.class);
                        handleIncomingResponse(requestId, responseReference);
                    } else if(packetType == PACKET_TYPE__RESPONSE_EXCEPTION) {
                        RequestId requestId = RequestId.readFrom(context);
                        GeneralException generalException = GeneralException.readFrom(context);
                        handleIncomingResponseGeneralException(requestId, generalException);
                    } else {
                        throw new IOException("Unknown internal packet type "+packetType);
                    }
                } catch (SocketException | EOFException e) {
                    try {
                        AbstractConnection.this.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    return;
                } catch (IOException e) {
                    //TODO print error and then go into 'reset-mode' (send message to server that we are fukked, then wait for an exact stream of bytes (the "reset-sequence") (read one byte at a time).)
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static class PendingRequest<T> {
        private final RequestId requestId;
        private final IRequest<T> request;
        private final Executor executor;
        private final IPacketListener<T> responsePacketListener;
        private final Consumer<RemoteException> onException;
        private final StackTraceElement[] callSiteStackTrace;

        public PendingRequest(RequestId requestId, IRequest<T> request, Executor executor, IPacketListener<T> responsePacketListener, Consumer<RemoteException> onException, StackTraceElement[] callSiteStackTrace) {
            this.requestId = requestId;
            this.request = request;
            this.executor = executor;
            this.responsePacketListener = responsePacketListener;
            this.onException = onException;
            this.callSiteStackTrace = callSiteStackTrace;
        }

        public void send(ISerializationContext context) throws IOException {
            requestId.writeTo(context);
            context.writeObject(request);
        }

        public void onResponse(T response) {
            if(executor != null) {
                executor.execute(() -> {
                    responsePacketListener.onPacket(response);
                });
            } else {
                responsePacketListener.onPacket(response);
            }
        }

        public void onGeneralException(GeneralException generalException) {
            RemoteException exception = new RemoteException(generalException.exceptionName, generalException.message, callSiteStackTrace);
            if(onException != null) {
                if(executor != null) {
                    executor.execute(() -> {
                        onException.accept(exception);
                    });
                } else {
                    onException.accept(exception);
                }
            } else {
                if(executor != null) {
                    executor.execute(() -> {
                        RuntimeException thrown = new RuntimeException(exception);
                        thrown.setStackTrace(stripStackTrace(thrown.getStackTrace(), 1));
                        throw thrown;
                    });
                } else {
                    //We don't want to crash connection thread, so just print...
                    try {
                        throw exception;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static class RequestId {
        private static final Random random = new Random();

        private final UUID uuid;

        public RequestId(UUID uuid) {
            this.uuid = uuid;
        }

        public static RequestId next() {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            randomBytes[6]  &= 0x0f;  /* clear version        */
            randomBytes[6]  |= 0x40;  /* set to version 4     */
            randomBytes[8]  &= 0x3f;  /* clear variant        */
            randomBytes[8]  |= 0x80;  /* set to IETF variant  */

            long msb = 0;
            long lsb = 0;
            for (int i=0; i<8; i++)
                msb = (msb << 8) | (randomBytes[i] & 0xff);
            for (int i=8; i<16; i++)
                lsb = (lsb << 8) | (randomBytes[i] & 0xff);
            return new RequestId(new UUID(msb, lsb));
        }

        public void writeTo(ISerializationContext context) throws IOException {
            context.writeLong(uuid.getMostSignificantBits());
            context.writeLong(uuid.getLeastSignificantBits());
        }

        public static RequestId readFrom(IDeserializationContext context) throws IOException {
            return new RequestId(new UUID(context.readLong(), context.readLong()));
        }

        @Override
        public boolean equals(Object obj) {
            if(this == obj) {
                return true;
            }
            if(obj instanceof final RequestId other) {
                return this.uuid.equals(other.uuid);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return uuid.hashCode();
        }
    }

    private static class RequestHandler {
        private final Executor executor;
        private final IRequestHandler requestHandler;

        public RequestHandler(Executor executor, IRequestHandler requestHandler) {
            this.executor = executor;
            this.requestHandler = requestHandler;
        }
    }

    private static class GeneralException {
        private final String exceptionName;
        private final String message;

        public GeneralException(Exception exception) {
            this(exception.getClass().getName(), exception.getMessage());
        }

        public GeneralException(String exceptionName, String message) {
            this.exceptionName = exceptionName;
            this.message = message;
        }

        public void writeTo(ISerializationContext context) throws IOException {
            context.writeString(exceptionName);
            context.writeString(message);
        }

        public static GeneralException readFrom(IDeserializationContext context) throws IOException {
            return new GeneralException(context.readString(), context.readString());
        }
    }

    private static StackTraceElement[] makeCallSiteStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        return stripStackTrace(stackTrace, 3);
    }

    private static StackTraceElement[] stripStackTrace(StackTraceElement[] stackTrace, int strip) {
        StackTraceElement[] result = new StackTraceElement[stackTrace.length - strip];
        System.arraycopy(stackTrace, strip, result, 0, result.length);
        return result;
    }
}
