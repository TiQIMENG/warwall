package cn.infinite.warwall.world.room;

import java.util.UUID;

public record RoomMember(UUID uuid, String name, CombatTeam team, boolean ready,
                         boolean online, boolean alive, long graceUntil) {
    public RoomMember team(CombatTeam value) { return new RoomMember(uuid, name, value, false, online, alive, graceUntil); }
    public RoomMember ready(boolean value) { return new RoomMember(uuid, name, team, value, online, alive, graceUntil); }
    public RoomMember online(boolean value, long until) { return new RoomMember(uuid, name, team, ready, value, alive, until); }
    public RoomMember alive(boolean value) { return new RoomMember(uuid, name, team, ready, online, value, graceUntil); }
    public boolean eligible(long now) { return alive && (online || now < graceUntil); }
}

