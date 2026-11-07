package cn.infinite.warwall.world.room;

public enum RoomState {
    PREPARING("地图准备中"), IDLE("空闲"), WAITING("等待玩家"),
    COUNTDOWN("即将开始"), STARTING("正在开局"), RUNNING("游戏进行中"),
    ENDING("本局已结束"), RESET_REQUIRED("等待新地图"), RECOVERY_REQUIRED("需要管理员检查");

    private final String display;
    RoomState(String display) { this.display = display; }
    public String display() { return display; }
}

