package cn.infinite.warwall.world.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/** 房主身份独立于 OP 和权限组，所有管理动作按本局 UUID 校验。 */
public final class SingleRoom {
    private final Map<UUID, RoomMember> members = new LinkedHashMap<>();
    private final Set<UUID> invitations = new java.util.HashSet<>();
    private final int capacity;
    private final RoomSettings defaults;
    private RoomState state;
    private RoomSettings settings;
    private UUID session = UUID.randomUUID();
    private UUID owner;
    private long countdownUntil, ownerMissingSince = -1;
    private final Map<CombatTeam, UUID> captains = new LinkedHashMap<>();
    private final Set<UUID> draftPool = new java.util.LinkedHashSet<>();
    private final Map<CombatTeam, UUID> designatedCaptains = new LinkedHashMap<>();
    private long revision;
    private UUID mainBoss;
    private boolean nativeDraft;
    private UUID nativeCaptain;
    private TerrainDraftView terrainDraft = TerrainDraftView.EMPTY;

    public boolean requiresTerrainSelection() { return settings.mode() == WarwallMode.NORMAL && settings.teamCount() == 4; }
    public TerrainDraftView terrainDraft() { return terrainDraft; }
    public void syncTerrainDraft(TerrainDraftView snapshot) {
        if (!nativeDraft) return;
        if (!members.keySet().containsAll(snapshot.candidates())) throw new IllegalArgumentException("队长必须属于本局。");
        if (!terrainDraft.equals(snapshot)) { terrainDraft = snapshot; changed(); }
    }

    public SingleRoom(int capacity, RoomSettings defaults, RoomState initialState) {
        if (capacity < 4 || capacity > 256) throw new IllegalArgumentException("房间人数上限应在 4～256 之间。");
        this.capacity = capacity; this.defaults = defaults; this.settings = defaults; this.state = initialState;
    }
    public RoomState state() { return state; }
    public RoomSettings settings() { return settings; }
    public UUID session() { return session; }
    public long revision() { return revision; }
    public UUID mainBoss() { return mainBoss; }
    public void selectBoss(UUID actor, UUID target) {
        requireOwner(actor); waiting();
        if (drafting()) throw new IllegalStateException("请先完成队长选人，再选择主 Boss。");
        if (settings.mode() != WarwallMode.BOSS) throw new IllegalStateException("只有 Boss 模式需要选择主 Boss。");
        RoomMember member = requireMember(target);
        if (!member.online() || member.team() != settings.bossTeam()) throw new IllegalStateException("主 Boss 必须是 Boss 队伍的在线成员。");
        mainBoss = target; members.replaceAll((id, value) -> value.ready(false)); changed();
    }
    private void checkBossSelection() {
        RoomMember member = mainBoss == null ? null : members.get(mainBoss);
        if (settings.mode() != WarwallMode.BOSS || member == null || member.team() != settings.bossTeam()) mainBoss = null;
    }
    public UUID owner() { return owner; }
    public int capacity() { return capacity; }
    public List<RoomMember> members() { return List.copyOf(members.values()); }
    public RoomMember member(UUID uuid) { return members.get(uuid); }
    public boolean isOwner(UUID uuid) { return uuid != null && uuid.equals(owner); }
    public int onlineCount() { return (int) members.values().stream().filter(RoomMember::online).count(); }
    public boolean drafting() { return nativeDraft; }
    public Set<UUID> draftPool() { return Set.copyOf(draftPool); }
    public Map<CombatTeam, UUID> captains() { return Map.copyOf(captains); }
    public Map<CombatTeam, UUID> designatedCaptains() { return Map.copyOf(designatedCaptains); }
    public void designateCaptain(UUID actor, CombatTeam team, UUID target) {
        requireOwner(actor); waiting();
        if (drafting()) throw new IllegalStateException("正在选人，请使用本轮队长转让功能或重新抽取。");
        if (!settings.activeTeams().contains(team)) throw new IllegalStateException("这支队伍没有启用。");
        if (!requireMember(target).online()) throw new IllegalStateException("队长必须在线。");
        if (designatedCaptains.entrySet().stream().anyMatch(entry -> entry.getKey() != team && entry.getValue().equals(target))) throw new IllegalStateException("同一名玩家不能兼任两队队长。");
        designatedCaptains.put(team, target);
        if (!requiresTerrainSelection()) members.put(target, members.get(target).team(team));
        else terrainDraft = TerrainDraftView.EMPTY;
        members.replaceAll((id, member) -> member.ready(false)); checkBossSelection(); changed();
    }
    public void clearDesignatedCaptains(UUID actor) {
        requireOwner(actor); waiting();
        if (drafting()) throw new IllegalStateException("请先取消队长选人，再清除指定队长。");
        designatedCaptains.clear(); changed();
    }
    public void validateCaptainTransfer(UUID actor, CombatTeam team, UUID target) {
        waiting();
        if (!drafting()) throw new IllegalStateException("请先启动队长分队。");
        if (requiresTerrainSelection() && terrainDraft.phase() != 3) throw new IllegalStateException("地形选完后才能转让队长；此前请取消后重选队长名单。");
        if (!settings.activeTeams().contains(team) || !captains.containsKey(team)) throw new IllegalStateException("这支队伍没有本轮队长。");
        if (!isOwner(actor) && !actor.equals(captains.get(team))) throw new IllegalStateException("只有房主或本队队长可以转让队长。");
        RoomMember member = requireMember(target);
        if (!member.online()) throw new IllegalStateException("新队长必须在线。");
        if (!isOwner(actor) && (draftPool.contains(target) || member.team() != team)) throw new IllegalStateException("队长只能转让给已经正式入队的本队成员。");
        if (captains.entrySet().stream().anyMatch(entry -> entry.getKey() != team && entry.getValue().equals(target))) throw new IllegalStateException("该玩家已经是另一队的队长。");
    }
    public UUID currentCaptain() { return nativeCaptain; }
    private void changed() { revision++; }
    private void stopDraft() { captains.clear(); draftPool.clear(); nativeDraft = false; nativeCaptain = null; terrainDraft = TerrainDraftView.EMPTY; }
    public void mapReady() {
        if (state != RoomState.PREPARING && state != RoomState.RESET_REQUIRED) throw new IllegalStateException("当前状态不允许标记新地图完成。");
        members.clear(); invitations.clear(); owner = null; settings = defaults;
        ownerMissingSince = -1;
        session = UUID.randomUUID(); state = RoomState.IDLE;
        stopDraft(); designatedCaptains.clear(); mainBoss = null; changed();
    }
    public void create(UUID uuid, String name, long now) {
        if (state != RoomState.IDLE) throw new IllegalStateException("当前房间" + state.display() + "，不能创建。");
        owner = uuid; state = RoomState.WAITING;
        CombatTeam first = settings.activeTeams().contains(CombatTeam.RED) ? CombatTeam.RED : settings.activeTeams().getFirst();
        members.put(uuid, new RoomMember(uuid, name, first, false, true, true, now));
        changed();
    }
    public RoomMember join(UUID uuid, String name, long now) {
        waiting();
        if (drafting()) throw new IllegalStateException("正在队长选人，完成或取消后才能加入。");
        RoomMember existing = members.get(uuid);
        if (existing != null) { reconnect(uuid, now); return members.get(uuid); }
        if (!settings.publicRoom() && !invitations.contains(uuid)) throw new IllegalStateException("这个房间需要房主邀请。");
        if (members.size() >= capacity) throw new IllegalStateException("房间已满。");
        CombatTeam team = settings.activeTeams().stream().min(Comparator.comparingLong(this::teamCount)).orElseThrow();
        RoomMember result = new RoomMember(uuid, name, team, false, true, true, now);
        terrainDraft = TerrainDraftView.EMPTY;
        members.put(uuid, result); changed(); return result;
    }
    public long teamCount(CombatTeam team) { return members.values().stream().filter(member -> member.team() == team).count(); }
    public void setTeam(UUID uuid, CombatTeam team) {
        waiting(); if (drafting()) throw new IllegalStateException("正在队长选人，不能自行换队。");
        if (requiresTerrainSelection()) throw new IllegalStateException("四队模式由队长先选地形、再选队员，不能自行换队。");
        if (!settings.activeTeams().contains(team)) throw new IllegalStateException("这支队伍没有启用。");
        RoomMember member = requireMember(uuid); members.put(uuid, member.team(team)); checkBossSelection(); changed();
    }
    public void ready(UUID uuid) {
        waiting(); if (drafting()) throw new IllegalStateException("队长选人完成后才能准备。");
        RoomMember member = requireMember(uuid); members.put(uuid, member.ready(!member.ready())); changed();
    }
    public void updateSettings(UUID actor, RoomSettings value) {
        requireOwner(actor); waiting();
        boolean teamsChanged = settings.teamCount() != value.teamCount() || settings.mode() != value.mode();
        stopDraft(); settings = value;
        if (teamsChanged) rebalance(new java.util.Random(0));
        if (teamsChanged) designatedCaptains.clear();
        members.replaceAll((uuid, member) -> member.ready(false));
        checkBossSelection();
        changed();
    }
    public void invite(UUID actor, UUID invited) { requireOwner(actor); waiting(); invitations.add(invited); }
    public void transfer(UUID actor, UUID target) {
        requireOwner(actor); waiting();
        if (!requireMember(target).online()) throw new IllegalStateException("只能转让给房间里的在线玩家。");
        owner = target; ownerMissingSince = -1; changed();
    }
    public void shuffle(UUID actor, java.util.Random random) {
        if (requiresTerrainSelection()) throw new IllegalStateException("四队模式请启动队长地形分队，不再自动分配领地。");
        requireOwner(actor); waiting(); stopDraft(); rebalance(random); checkBossSelection(); changed();
    }
    private void rebalance(java.util.Random random) {
        List<UUID> ids = new ArrayList<>(members.keySet());
        java.util.Collections.shuffle(ids, random);
        List<CombatTeam> teams = settings.activeTeams();
        for (int i = 0; i < ids.size(); i++) { UUID id = ids.get(i); members.put(id, members.get(id).team(teams.get(i % teams.size()))); }
    }
    public void beginDraft(UUID actor, boolean reroll) {
        requireOwner(actor); waiting();
        if (settings.teamCount() < 2) throw new IllegalStateException("一队模式不需要队长选人。");
        if (onlineCount() < settings.teamCount() || members.values().stream().anyMatch(member -> !member.online())) throw new IllegalStateException("每队至少需要一名在线队长，所有房间成员都要在线。");
        stopDraft(); nativeDraft = true; draftPool.addAll(members.keySet());
        if (reroll) designatedCaptains.clear();
        members.replaceAll((id, member) -> member.ready(false)); checkBossSelection(); changed();
    }
    public void pick(UUID actor, UUID target) {
        waiting();
        if (!drafting()) throw new IllegalStateException("当前没有队长选人。");
        if (requiresTerrainSelection() && terrainDraft.phase() != 3) throw new IllegalStateException("请先完成四名队长确认和地形选择。");
        if (!actor.equals(currentCaptain())) throw new IllegalStateException("还没轮到你选人。");
        if (!draftPool.contains(target) || !requireMember(target).online()) throw new IllegalStateException("这名玩家已经被选走或不在线。");
    }
    /** 只接收数据包本轮队伍和选择标签的读回结果，不自行维护第二套选人流程。 */
    public void syncNativeDraft(Map<UUID, CombatTeam> assigned, Map<CombatTeam, UUID> leaders, Set<UUID> pending, UUID choosing, boolean active) {
        if (!nativeDraft) return;
        boolean changed = !captains.equals(leaders) || !draftPool.equals(pending) || !Objects.equals(nativeCaptain, choosing) || nativeDraft != active;
        for (var entry : assigned.entrySet()) {
            RoomMember member = members.get(entry.getKey());
            if (member != null && settings.activeTeams().contains(entry.getValue()) && member.team() != entry.getValue()) {
                members.put(entry.getKey(), member.team(entry.getValue())); changed = true;
            }
        }
        captains.clear(); captains.putAll(leaders); draftPool.clear(); draftPool.addAll(pending);
        nativeCaptain = choosing; nativeDraft = active; checkBossSelection();
        if (changed) changed();
    }
    public void cancelDraft(UUID actor) {
        requireOwner(actor); waiting();
        if (!drafting()) throw new IllegalStateException("当前没有队长选人。");
        stopDraft(); members.replaceAll((id, member) -> member.ready(false)); changed();
    }
    public void requestStart(UUID actor, long now, int countdownSeconds) {
        requireOwner(actor); waiting(); validateStart();
        if (countdownSeconds < 1 || countdownSeconds > 3600) throw new IllegalArgumentException("倒计时应在 1～3600 秒之间。");
        state = RoomState.COUNTDOWN; countdownUntil = now + Math.multiplyExact(countdownSeconds, 1000L);
        changed();
    }
    public boolean advanceCountdown(long now) {
        if (state != RoomState.COUNTDOWN || now < countdownUntil) return false;
        try { validateStart(); } catch (RuntimeException exception) { state = RoomState.WAITING; changed(); throw exception; }
        state = RoomState.STARTING; changed(); return true;
    }
    public long countdownSeconds(long now) { return Math.max(0, (countdownUntil - now + 999) / 1000); }
    public void cancelCountdown(UUID actor) {
        requireOwner(actor);
        if (state != RoomState.COUNTDOWN) throw new IllegalStateException("当前没有开局倒计时。");
        state = RoomState.WAITING; changed();
    }
    public void started() {
        if (state != RoomState.STARTING) throw new IllegalStateException("房间不在开局状态。");
        state = RoomState.RUNNING; members.replaceAll((uuid, member) -> member.alive(true)); changed();
    }
    public void startFailed() { state = RoomState.RECOVERY_REQUIRED; changed(); }
    public void preparationFailed(boolean unsuitableSeed) {
        state = unsuitableSeed ? RoomState.RESET_REQUIRED : RoomState.RECOVERY_REQUIRED;
        changed();
    }
    public void finish() {
        if (state != RoomState.RUNNING && state != RoomState.STARTING) throw new IllegalStateException("房间没有正在进行的游戏。");
        state = RoomState.ENDING; changed();
    }
    public void requireReset() { state = RoomState.RESET_REQUIRED; changed(); }
    public void preparing() { if (owner != null) throw new IllegalStateException("还有房主，不能准备地图。"); state = RoomState.PREPARING; changed(); }
    public void close(UUID actor) {
        requireOwner(actor); waiting(); members.clear(); invitations.clear(); owner = null;
        ownerMissingSince = -1;
        settings = defaults; session = UUID.randomUUID(); state = RoomState.IDLE;
        stopDraft(); designatedCaptains.clear(); mainBoss = null; changed();
    }
    public void leave(UUID uuid) {
        if (state != RoomState.WAITING && state != RoomState.COUNTDOWN) throw new IllegalStateException("对局已开始，离开会按断线处理。");
        requireMember(uuid);
        if (state == RoomState.COUNTDOWN) state = RoomState.WAITING;
        stopDraft();
        members.remove(uuid); invitations.remove(uuid);
        designatedCaptains.values().removeIf(uuid::equals);
        if (isOwner(uuid)) { owner = members.values().stream().filter(RoomMember::online).map(RoomMember::uuid).findFirst().orElse(null); }
        if (owner == null) { members.clear(); invitations.clear(); designatedCaptains.clear(); mainBoss = null; ownerMissingSince = -1; state = RoomState.IDLE; settings = defaults; session = UUID.randomUUID(); }
        checkBossSelection();
        changed();
    }
    public void disconnect(UUID uuid, long now, long graceMillis) {
        RoomMember member = members.get(uuid); if (member == null) return;
        members.put(uuid, member.online(false, now + graceMillis));
        if (isOwner(uuid)) ownerMissingSince = now;
        if (state == RoomState.COUNTDOWN) state = RoomState.WAITING;
        if (drafting()) { stopDraft(); members.replaceAll((id, value) -> value.ready(false)); }
        changed();
    }
    public void reconnect(UUID uuid, long now) {
        RoomMember member = members.get(uuid); if (member == null) return;
        if (state == RoomState.RUNNING && !member.online() && now >= member.graceUntil()) member = member.alive(false);
        members.put(uuid, member.online(true, 0));
        if (isOwner(uuid)) ownerMissingSince = -1;
        changed();
    }
    public void expireWaiting(long now, long ownerGraceMillis) {
        if (state != RoomState.WAITING) return;
        Set<UUID> previous = Set.copyOf(members.keySet());
        boolean expired = members.entrySet().removeIf(entry -> !entry.getValue().online() && !entry.getKey().equals(owner) && now >= entry.getValue().graceUntil());
        if (ownerMissingSince >= 0 && now - ownerMissingSince >= ownerGraceMillis) {
            UUID next = members.values().stream().filter(RoomMember::online).map(RoomMember::uuid).findFirst().orElse(null);
            if (next != null) { members.remove(owner); owner = next; ownerMissingSince = -1; }
            else {
                members.clear(); invitations.clear(); owner = null; ownerMissingSince = -1;
                state = RoomState.IDLE; session = UUID.randomUUID(); settings = defaults; stopDraft();
            }
            expired = true;
        }
        if (expired) {
            stopDraft();
            members.replaceAll((id, member) -> member.ready(false));
            invitations.removeIf(id -> previous.contains(id) && !members.containsKey(id));
            designatedCaptains.values().removeIf(id -> !members.containsKey(id));
            checkBossSelection(); changed();
        }
    }
    public void updateAlive(UUID uuid, boolean alive) {
        RoomMember member = members.get(uuid);
        if (member != null && member.alive() != alive) members.put(uuid, member.alive(alive));
    }
    public void removeMember(UUID actor, UUID target) {
        requireOwner(actor); waiting();
        if (isOwner(target)) throw new IllegalStateException("不能移出自己，可以关闭房间或转让房主。");
        requireMember(target); stopDraft(); members.remove(target); invitations.remove(target); designatedCaptains.values().removeIf(target::equals); checkBossSelection(); changed();
    }
    public Set<CombatTeam> eligibleTeams(long now) {
        Set<CombatTeam> result = EnumSet.noneOf(CombatTeam.class);
        members.values().stream().filter(member -> member.eligible(now)).forEach(member -> result.add(member.team()));
        return Set.copyOf(result);
    }
    public boolean blocksNativeWin(long now, boolean nativeWitherPresent) {
        if (state != RoomState.RUNNING) return false;
        int remaining = eligibleTeams(now).size();
        return remaining > 1 || (nativeWitherPresent && remaining > 0);
    }
    public void validateStart() {
        if (drafting()) throw new IllegalStateException("队长选人尚未完成。");
        if (requiresTerrainSelection() && !terrainDraft.complete()) throw new IllegalStateException("四队模式必须先完成队长确认、地形选择和反向选人。");
        if (onlineCount() < settings.teamCount()) throw new IllegalStateException("至少需要 " + settings.teamCount() + " 名在线玩家，并且每队都有人。");
        for (CombatTeam team : settings.activeTeams()) {
            if (members.values().stream().noneMatch(member -> member.online() && member.team() == team)) throw new IllegalStateException(team.display() + "还没有在线玩家。");
        }
        if (members.values().stream().anyMatch(member -> !settings.activeTeams().contains(member.team()))) throw new IllegalStateException("有成员被分到了未启用的队伍，请重新分队。");
        if (members.values().stream().anyMatch(member -> !member.online())) throw new IllegalStateException("还有成员未返回房间，可以等他回来或移出本局。");
        if (settings.requireReady() && members.values().stream().anyMatch(member -> !member.ready())) throw new IllegalStateException("还有成员没有准备。");
        if (settings.mode() == WarwallMode.BOSS) {
            RoomMember boss = mainBoss == null ? null : members.get(mainBoss);
            if (boss == null || !boss.online() || boss.team() != settings.bossTeam()) throw new IllegalStateException("请先选择 Boss 队伍的一名在线玩家作为主 Boss。");
            long bossPlayers = teamCount(settings.bossTeam()), challengers = members.size() - bossPlayers;
            // 原生公式是 20 + 4 ×（挑战者人数 - Boss 队人数），不改写其数值。
            if (20 + 4 * (challengers - bossPlayers) <= 0) throw new IllegalStateException("Boss 队人数过多，主 Boss 的原生血量会低于 1。请调整两队人数再开局。");
            if (challengers + 1 - bossPlayers < 1) throw new IllegalStateException("Boss 队人数不能多于挑战者人数，否则原生技能物品数量无效。请调整两队人数再开局。");
        }
    }
    public void requireOwner(UUID actor) { if (!isOwner(actor)) throw new IllegalStateException("只有本局房主可以操作。"); }
    private RoomMember requireMember(UUID uuid) { RoomMember member = members.get(uuid); if (member == null) throw new IllegalStateException("你还没有加入房间。"); return member; }
    private void waiting() { if (state != RoomState.WAITING) throw new IllegalStateException("房间" + state.display() + "，现在不能修改。"); }
}

