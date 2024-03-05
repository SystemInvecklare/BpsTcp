package net.pointlessgames.libs.bpstcp.connection;

import java.io.IOException;
import java.net.Socket;

public class ClientToServerConnection<IncomingPacketType, OutgoingPacketType> extends AbstractConnection<IncomingPacketType, OutgoingPacketType> implements IClientToServerConnection<IncomingPacketType, OutgoingPacketType> {
    public ClientToServerConnection(NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization) {
        super(serialization);
    }

    @Override
    public void connect(String host, int port) throws IOException {
        connect(new Socket(host, port), () -> {});
    }

    public static <IncomingPacketType, OutgoingPacketType> ClientToServerConnection<IncomingPacketType, OutgoingPacketType> using(NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization) {
        return new ClientToServerConnection<>(serialization);
    }
}
