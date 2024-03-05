package net.pointlessgames.libs.bpstcp.connection;

import net.pointlessgames.libs.bpstcp.exception.RemoteException;
import net.pointlessgames.libs.bpstcp.request.IAwaitableRequestTicket;
import net.pointlessgames.libs.bpstcp.request.IRequest;
import net.pointlessgames.libs.bpstcp.request.IRequestHandler;
import net.pointlessgames.libs.bpstcp.request.IRequestTicket;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface IConnection<IncomingPacketType, OutgoingPacketType> extends Closeable {
    void addListener(Executor executor, IPacketListener<IncomingPacketType> listener);
    void setRequestHandler(Executor executor, IRequestHandler requestHandler);
    void send(OutgoingPacketType packet);
    <T> IRequestTicket<T> send(IRequest<T> request, Executor executor, IPacketListener<T> responsePacketListener);
    <T> IRequestTicket<T> send(IRequest<T> request, Executor executor, IPacketListener<T> responsePacketListener, Consumer<RemoteException> onException);
    <T> IAwaitableRequestTicket<T> send(IRequest<T> request, Executor executor);
    void addDisconnectListener(Executor executor, Runnable onDisconnected);
}
