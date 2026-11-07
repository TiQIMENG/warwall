package cn.infinite.warwall.world.paper;

/** 原生函数可能不换阶段编号就重置计时，例如连续刷新死斗果实。 */
public final class NativePhaseClock {
    private int phase = -1, timer = -1;
    public boolean resetRequired(int currentPhase, int currentTimer) {
        return currentPhase != phase || currentTimer > timer + 1L;
    }
    public void record(int currentPhase, int currentTimer) { phase = currentPhase; timer = currentTimer; }
}

