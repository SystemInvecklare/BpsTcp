package net.pointlessgames.libs.bpstcp.connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.function.Consumer;

public class ServerToClientConnection<IncomingPacketType, OutgoingPacketType> extends AbstractConnection<IncomingPacketType, OutgoingPacketType> implements IConnection<IncomingPacketType, OutgoingPacketType> {
    private ServerToClientConnection(NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization) {
        super(serialization);
    }

    public InetSocketAddress getRemoteAddress() {
        Socket socket = this.getSocket();
        if(socket == null) {
            throw new IllegalStateException("Not connected");
        }
        SocketAddress socketAddress = socket.getRemoteSocketAddress();
        if(socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }

    public static <IncomingPacketType, OutgoingPacketType> void server(NetworkSerialization<IncomingPacketType, OutgoingPacketType> serialization, int port, Consumer<ServerToClientConnection<IncomingPacketType, OutgoingPacketType>> onAccept) throws IOException, InterruptedException {
        ServerSocket serverSocket = new ServerSocket(port);
        while (!Thread.interrupted()) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            ServerToClientConnection<IncomingPacketType, OutgoingPacketType> clientConnection = new ServerToClientConnection<>(serialization);
            clientConnection.connect(socket, () -> {
                onAccept.accept(clientConnection);
            });
        }
        throw new InterruptedException();
    }
}
