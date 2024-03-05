package net.pointlessgames.libs.bpstcp.request;

public interface IRequestTicket<T> {
    void cancel();
    boolean isPending();
}
