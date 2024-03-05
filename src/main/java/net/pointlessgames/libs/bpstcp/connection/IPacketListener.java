package net.pointlessgames.libs.bpstcp.connection;

public interface IPacketListener<PacketType> {
    void onPacket(PacketType packet);
}
