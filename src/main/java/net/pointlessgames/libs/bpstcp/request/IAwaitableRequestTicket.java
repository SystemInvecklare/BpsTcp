package net.pointlessgames.libs.bpstcp.request;

import net.pointlessgames.libs.bpstcp.connection.IPacketListener;

import java.util.function.Consumer;

public interface IAwaitableRequestTicket<T> extends IRequestTicket<T> {
    T await();
    default void onResponse(IPacketListener<T> responsePacketListener) {
        onResponse(responsePacketListener, null);
    }

    void onResponse(IPacketListener<T> responsePacketListener, Consumer<Throwable> onException);
}
