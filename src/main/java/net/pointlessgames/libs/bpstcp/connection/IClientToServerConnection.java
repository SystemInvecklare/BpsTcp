package net.pointlessgames.libs.bpstcp.connection;

import java.io.IOException;

public interface IClientToServerConnection<IncomingPacketType, OutgoingPacketType> extends IConnection<IncomingPacketType, OutgoingPacketType> {
    void connect(String host, int port) throws IOException;
}
