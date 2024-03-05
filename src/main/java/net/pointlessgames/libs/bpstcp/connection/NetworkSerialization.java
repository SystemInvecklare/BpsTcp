package net.pointlessgames.libs.bpstcp.connection;

import net.pointlessgames.libs.bps.IDeserializer;
import net.pointlessgames.libs.bps.ISerializer;

public class NetworkSerialization<IncomingPacketType, OutgoingPacketType> {
    public final IDeserializer<IncomingPacketType> incomingPacketDeserializer;
    public final IDeserializer<Object> incomingObjectDeserializer;
    public final ISerializer<OutgoingPacketType> outgoingPacketSerializer;
    public final ISerializer<Object> outgoingObjectSerializer;

    public NetworkSerialization(IDeserializer<IncomingPacketType> incomingPacketDeserializer, IDeserializer<Object> incomingObjectDeserializer, ISerializer<OutgoingPacketType> outgoingPacketSerializer, ISerializer<Object> outgoingObjectSerializer) {
        this.incomingPacketDeserializer = incomingPacketDeserializer;
        this.incomingObjectDeserializer = incomingObjectDeserializer;
        this.outgoingPacketSerializer = outgoingPacketSerializer;
        this.outgoingObjectSerializer = outgoingObjectSerializer;
    }

    public static class Symmetric<IncomingPacketType, OutgoingPacketType> extends NetworkSerialization<IncomingPacketType, OutgoingPacketType> {
        public Symmetric(ISerializer<IncomingPacketType> incomingPacketDeserializer, ISerializer<Object> incomingObjectDeserializer, ISerializer<OutgoingPacketType> outgoingPacketSerializer, ISerializer<Object> outgoingObjectSerializer) {
            super(incomingPacketDeserializer, incomingObjectDeserializer, outgoingPacketSerializer, outgoingObjectSerializer);
        }

        public Symmetric<OutgoingPacketType, IncomingPacketType> flip() {
            return new Symmetric(outgoingPacketSerializer, outgoingObjectSerializer, (ISerializer<IncomingPacketType>) incomingPacketDeserializer, (ISerializer<Object>) incomingObjectDeserializer);
        }
    }
}
