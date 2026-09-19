package com.november.mcphone.feature.hotkey.client;

import com.november.mcphone.MCphone;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 让某一个第三方模组的键位功能响一次 —— 不按键、不模拟 GLFW、不反射它的类。
 *
 * <h2>这是干什么的</h2>
 *
 * 科技模组盔甲的模块键、鞘翅模组的模式切换键，一年按不了几次，却各占一个顺手键位。
 * 这个类让它们在手机里点得到：拿到那个 {@link KeyMapping}，往它自己的「按下边沿队列」
 * 里放一次，模组自己那段 {@code while (key.consumeClick())} 就会照常跑。
 * 真正动字段的那几句在 {@link HotkeyBackend} 里，三个目标各一份：neoforge 与 forge 用 AT 把那个私有字段
 * 放开（Forge 那份的条目写的是 SRG 名），fabric 用 access widener。
 *
 * <h2>两条路：先问声明，再问结果</h2>
 *
 * 有一类模组在 {@code mc.screen != null} 时会跳过自己的轮询（自己写了一句
 * {@code if (mc.screen != null) return;}），而手机界面<b>就是</b>一个 {@code Screen}。
 * 对它们来说，手机开着时注入进去的那一下没人取。
 *
 * <p>更要紧的是：Forge 与 NeoForge 都给键位加了「上下文」，{@code KeyConflictContext.IN_GAME} 的
 * 定义就是「没有界面才算数」，而做游戏内动作的键位基本都选它 —— 于是同一个原因还有
 * 第二个入口（{@code isDown()} 那条路会判上下文；{@code consumeClick()} 本身<b>不</b>判，
 * 所以「点一下＝按一次」这条路在手机开着时仍然可能直接生效，不必一律先关界面）。
 * 玩家在列表里看到的那个小标签就是它，见 {@link HotkeyContext}。
 *
 * <p><b>先问声明</b>：声明了只在世界里生效的（{@link HotkeyContext#WORLD}）直接收起手机再注入，
 * 一次到位 —— 不做那一下注定没人取的就地注入，玩家看到的就是"点一下、手机让开、动作发生"。
 *
 * <p><b>再问结果</b>：没声明的（原版键位与 {@code UNIVERSAL} 都是这个）先就地注入，
 * 隔一拍读回那个计数器。归 0 ＝ 有模组轮询到了，这一下真的生效了；纹丝不动 ＝ 没人在看它，
 * 这才收起手机再注入一次 —— 那时 {@code mc.screen == null}，判了界面的模组就看得见了。
 * 两次都没人取，就是这个键位手机触发不了，如实告诉玩家。
 *
 * <h2>每一条出口都要把队列收回来</h2>
 *
 * <b>「关掉界面会把队列清干净」是假的。</b> {@code KeyMapping.releaseAll()} 在
 * {@code Minecraft.setScreen} 里那一句落在 {@code if (guiScreen != null)} 分支中 ——
 * <b>只有开界面才清，关界面走 else 分支，什么都不清</b>（1.21.1 原版，全游戏只有这一个调用点）。
 *
 * <p>所以这个类必须自己负责：凡是「我们注进去的那一次没人消费」的出口
 * （放弃触发、等超时、中途离开世界、以及收起手机之后重新注入之前），都要
 * {@link HotkeyBackend#reset} 一次。不收的话它会一直躺在队列里，等那个模组以后
 * 开始轮询时<b>迟到地响一次</b> —— 玩家看到一个自己没按过的键位突然生效，
 * 而且无从解释。宁可这一次不响应。
 *
 * <h2>收起界面这一下为什么是 {@code mc.setScreen(null)}，不是 {@link Screen#onClose()}</h2>
 *
 * 因为 {@code onClose()} 并不是"关掉界面"。它在 1.21.1 里的实现是
 * {@code minecraft.popGuiLayer()}，而 {@code ClientHooks.popGuiLayer} 在 GUI 层栈非空时
 * 做的是<b>把下面那一层恢复出来</b>（栈空才退化成 {@code setScreen(null)}）——
 * 于是"关手机"会变成"手机关不掉、只闪一下"。本功能要的就是关掉，所以直接说 null。
 *
 * <p>该跑的收尾一样会跑：{@code setScreen} 会调旧界面的 {@code removed()}，而手机的真收尾
 * 在那里（{@code PhoneScreen.removed → shutdown}）。手机那边并没有在 {@code onClose()} 里
 * 做任何事（{@code PhoneScreen.onClose()} 只有一句 {@code super.onClose()}），跳过它不丢东西。
 *
 * <h2>收起手机之后，副手 HUD 上那部还亮着</h2>
 *
 * 手机挂在副手 HUD 上时它是<b>一直开着</b>的（见 {@code PhoneHud}）：收起全屏那副面孔不等于
 * 关机 —— 它会回到屏幕角落继续亮着，玩家再按一次唤出键才回到正中。所以"点完手机还在"
 * 可能只是这个，不是没关掉；日志里那行 {@code 收起手机之后 mc.screen 仍然不是空的} 才是
 * 真的判断依据。
 *
 * <h2>一次只处理一个</h2>
 *
 * 上一个还没走完就再来一下的话，直接忽略：重开一个的话，上一个已经注入、还没人取的那一次
 * 会留在队列里，等模组下一轮轮询时<b>迟到地响一次</b> —— 玩家会看到动作莫名其妙发生。
 * 宁可这一下不响应（玩家再点一次就是），也不要制造一个延迟的意外。
 *
 * <h2>覆盖边界（要如实说，也是 App 页面要显示的那句话）</h2>
 *
 * <ul>
 *   <li>覆盖：在自己的 tick 里轮询 {@code consumeClick()} 的模组 —— 这类占多数；</li>
 *   <li>不覆盖：按住类（读 {@code isDown()} 连续生效的）—— 这个功能只做「点一下＝按一次」；</li>
 *   <li>不覆盖：自己听原始输入事件、或直接查物理按键状态的模组。本仓自己就有一个这样的键
 *       （{@code PhoneKeys.HUD_INTERACT}，它必须查 {@code InputConstants.isKeyDown}）——
 *       这类会被 {@link Outcome#UNSUPPORTED} 如实识别出来，不是静默失灵。</li>
 * </ul>
 *
 * <h2>结果怎么交给界面</h2>
 *
 * 结果可能产生在页面已经被销毁之后（收起手机那一条路必然如此），所以它留在本类里，
 * 由页面在<b>全屏那一帧</b> {@link #consumeOutcome() 取走}。<b>本类不负责显示</b>，
 * 页面也不该在 {@code onClose} 时把它清掉 —— 那样这句话就永远不会被玩家看到。
 *
 * <p><b>为什么强调"全屏那一帧"</b>：副手 HUD 上那部手机是不关机的（{@code PhoneScreen.removed}
 * 里的 {@code hudOwned} 那一格），所以收起全屏那副面孔之后，这一页会继续在屏幕角落被
 * 每帧渲染（{@code PhoneHud.render → renderAsHud}），位置编辑器的预览也是同一条路。
 * 要是让角落那一份把结果取走，玩家重开手机时它已经没了 —— 那句话就成了只有缩略图见过的东西。
 *
 * <p>它另外有两条寿命规矩：被取走即清（不重放）；离开世界时无条件清掉（见 {@link #tick()}）
 * —— 那句话是给玩家在<b>这一局</b>里看的，断线之后换个世界再弹出来是无中生有。
 */
public final class KeyTrigger {

    private KeyTrigger() {}

    /** 一次触发的结果。界面拿它显示「这个键位手机触发不了」 */
    public enum Outcome {
        /** 还没有结果，或者上一条结果已经被界面取走 */
        NONE,
        /** 模组把这一下取走了 —— 真的生效了 */
        OK,
        /** 注入两次都没人消费：这个模组不通过 KeyMapping 消费键盘，手机触发不了它 */
        UNSUPPORTED
    }

    /** 注入之后等几拍再判「没人取」。一拍是 50ms；给两拍是为了容忍模组在 tick 里晚一步轮询 */
    private static final int GRACE_TICKS = 2;

    /**
     * 单段的等待上限（拍）。
     *
     * <p>不能没有：模组的轮询有可能永远不看它，而没有上限的状态机会一直挂在 pending 上，
     * 于是玩家之后再点任何键位都被「一次只处理一个」挡掉 —— 手机看起来就是坏了。
     */
    private static final int MAX_TICKS = 20;

    private enum Stage {
        /** 界面开着时就地注入的，等模组消费（键位没声明只在世界里生效的那一类） */
        INJECTED_IN_GUI,
        /** 键位声明了只在世界里生效：先请手机让开，注入留到没有界面之后 —— 一次到位 */
        CLOSE_FIRST,
        /** 没人消费，已经请界面关掉，等它真的关掉 */
        CLOSING,
        /** 界面关掉之后重新注入的，等模组消费 */
        INJECTED_NO_GUI
    }

    private static final class Pending {
        final KeyMapping mapping;
        Stage stage;
        int age;

        /** 我们请让开的那个界面。用来分辨"关不掉"是不是它自己又回来了 */
        Screen closed;

        /** "收起之后还有界面挡着"这件事只记一行日志，别每拍刷一次 */
        boolean warnedBlocked;

        Pending(KeyMapping mapping) {
            this.mapping = mapping;
        }
    }

    private static Pending pending;

    private static Outcome outcome = Outcome.NONE;

    /** 这个目标能不能真的触发（false 时 App 根本不登记，见 {@link HotkeyBackend#available()}） */
    public static boolean available() {
        return HotkeyBackend.available();
    }

    /**
     * 触发一次。
     *
     * <p>上一个还没走完时忽略这一下，理由见类注释「一次只处理一个」。
     */
    public static void trigger(KeyMapping mapping) {
        if (mapping == null || !available()) return;
        if (pending != null) {
            MCphone.LOGGER.info("[MCphone] 遥控器：上一个（{}）还没走完，忽略这一下",
                    pending.mapping.getName());
            return;
        }

        // 新的一次触发作废上一条结果，免得界面把上一次的"触发不了"挂在这一行上
        outcome = Outcome.NONE;

        Pending p = new Pending(mapping);
        Minecraft mc = Minecraft.getInstance();

        // 键位自己声明了"只在世界里生效"（Forge / NeoForge 的 KeyConflictContext.IN_GAME）时，
        // 手机开着注入的那一次它不会取 —— 手机界面就是一个界面。那就别浪费这一下：
        // 直接请手机让开，注入留到没有界面之后，玩家看到的是一次到位。
        // 见 HotkeyContext 的类注释。
        if (mc.screen != null && HotkeyBackend.contextOf(mapping).closePhoneFirst()) {
            p.stage = Stage.CLOSE_FIRST;
            pending = p;
            MCphone.LOGGER.info("[MCphone] 遥控器：{} 声明只在世界里生效，先收起手机再触发",
                    mapping.getName());
            return;
        }

        // 界面开着也先直接注入：consumeClick() 不判上下文，不判界面的模组当场就能吃到，
        // 那样玩家不用为了按一下而退出手机
        HotkeyBackend.reset(mapping);
        HotkeyBackend.injectClick(mapping);
        p.stage = mc.screen == null ? Stage.INJECTED_NO_GUI : Stage.INJECTED_IN_GUI;
        pending = p;

        MCphone.LOGGER.info("[MCphone] 遥控器：{}（阶段 {}）", mapping.getName(), p.stage);
    }

    /**
     * 取走最近一次结果，取走之后就没了。
     *
     * <p>「取走」而不是「读」：结果可能产生在页面销毁之后，页面下次进来时取它一次就够，
     * 不该每次开这个 App 都把上一次的旧结果重放一遍。
     */
    public static Outcome consumeOutcome() {
        Outcome out = outcome;
        outcome = Outcome.NONE;
        return out;
    }

    /**
     * 由 {@link com.november.mcphone.core.client.ClientTicks#tick()} 每客户端 tick 调一次。
     *
     * <p>放那儿而不是各目标自己订阅：那个文件就是共用侧「要 tick 的功能」的唯一入口，
     * 它的类注释写着「加一个功能＝在这个文件里加一行，平台文件一个都不用动」。
     */
    public static void tick() {
        Pending p = pending;

        if (!available()) {
            // 【这里不能调 reset】：三份 HotkeyBackend 的 available() 与那几个动作方法是一体的 ——
            // false 意味着这一档压根没接上（没开 clickCount 的口子、或者放开字段的那份声明没生效），
            // 那时 reset 同样不可信。所以只能丢掉 + 留一行日志。
            //
            // 今天走不到这里：能建出 pending 必先过 trigger 的 available() 门，而三份
            // HotkeyBackend.available() 现在都返回常量 true（neoforge 与 forge 是 AT、
            // fabric 是 access widener，三边都把 KeyMapping.clickCount 放开了）。写在这儿是为了
            // 它哪天变成运行期可变（比如加一个配置开关）时不至于变成"注入永远残留、而且一声不响"
            // —— 那一天得先给那一档一个不抛的 reset。
            if (p != null) {
                pending = null;
                MCphone.LOGGER.warn("[MCphone] 遥控器：目标中途变得不可用了，这一次触发作废"
                        + "（队列无法收回：这一档不能调 reset，见上）");
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // 不在世界里：待办的那一次作废，上一条结果也一并清掉。
            //
            // 【为什么结果也要清，为什么这一段要放在 p == null 的早退之前】：
            // 「触发不了」那句话是给玩家在【这一局】里看的，断线之后换个世界再弹出来
            // 是无中生有。而结果与 pending 是各自独立的两个字段 —— 放在早退之后的话，
            // "没有待办、但留着一条结果"这个最常见的组合就永远清不掉。
            outcome = Outcome.NONE;
            if (p == null) return;

            // 队列照收 —— 它挂在 mapping 那个对象上，而对象不会跟着世界消失
            HotkeyBackend.reset(p.mapping);
            pending = null;
            MCphone.LOGGER.info("[MCphone] 遥控器：{} 中途离开了世界，这一次作废（注入已收回）",
                    p.mapping.getName());
            return;
        }

        if (p == null) return;

        p.age++;
        if (p.age > MAX_TICKS) {
            giveUp(p, "等太久了");
            return;
        }

        // 归 0 就是被消费了。唯一会误判的窗口：这期间有别的界面被打开
        // （setScreen 非 null 时会 releaseAll 把队列清零）。而本页 capturesKeyboard() 为 true
        // 会吃掉所有按键、点手机外面是"关"不是"开"，所以玩家自己走不出这条路径；
        // 窗口也只有两拍。机制上存在，眼下够不着，记在这儿备查。
        boolean consumed = HotkeyBackend.pending(p.mapping) == 0;

        switch (p.stage) {
            case INJECTED_IN_GUI -> {
                if (consumed) {
                    succeed(p);
                } else if (p.age >= GRACE_TICKS) {
                    // 没人取：多半是它判了 mc.screen == null。
                    // 【先把这一下收回来再关界面】：关界面不会清队列（见类注释），
                    // 不收的话它会留着，等模组以后轮询时迟到地响一次
                    HotkeyBackend.reset(p.mapping);
                    askScreenToLeave(p, mc);
                }
            }
            case CLOSE_FIRST -> {
                // 键位自己声明了"界面开着时不算数"，所以这一次连试都不试：直接请手机让开，
                // 注入留到没有界面之后（见 HotkeyContext 的类注释）。
                //
                // 【这一档不能看 consumed】：本阶段压根没注入过，队列本来就是空的，
                // 拿它当"有人消费了"会把一次根本没发生的触发报成成功
                HotkeyBackend.reset(p.mapping);
                askScreenToLeave(p, mc);
            }
            case CLOSING -> {
                Screen now = mc.screen;
                if (now != null) {
                    // 还没关干净，再等一拍。第一次发现有界面挡着时把"是谁"记下来：
                    // 正常情况一拍就没了；一直不走，说明有别的东西把界面又顶了回来，
                    // 而玩家那边只会看到"手机没关掉"。那一行日志是唯一的线索。
                    if (!p.warnedBlocked) {
                        p.warnedBlocked = true;
                        MCphone.LOGGER.warn("[MCphone] 遥控器：收起手机之后 mc.screen 仍然不是空的"
                                        + "（{}）—— {}",
                                now.getClass().getName(),
                                now == p.closed ? "刚收起来的那个界面又回来了"
                                                : "顶上来的是另一个界面");
                    }
                    return;
                }
                injectWithNoScreen(p);
            }
            case INJECTED_NO_GUI -> {
                if (consumed) {
                    succeed(p);
                } else if (p.age >= GRACE_TICKS) {
                    giveUp(p, "没有模组消费");
                }
            }
        }
    }

    /**
     * 请当前这个界面让开，然后进入 {@link Stage#CLOSING} 等它真的让开。
     *
     * <p>界面已经不在了（玩家自己把手机收了）就直接走"没有界面"那一步 —— 那正是我们要的
     * 状态，没有理由放弃：进到这里的两条路都刚收过队列，此刻注入进去不会与谁混在一起。
     */
    private static void askScreenToLeave(Pending p, Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == null) {
            injectWithNoScreen(p);
            return;
        }

        // 【为什么是 setScreen(null) 而不是 screen.onClose()】：onClose() 走的是 popGuiLayer()，
        // GUI 层栈非空时它恢复的是下面那一层 —— "手机关不掉、只闪一下"就是这么来的。
        // 详见类注释那一节。
        //
        // 【为什么要 catch】：最可能的一种是原版的前置断言 —— Minecraft.setScreen 开头就是
        //   if (guiScreen == null && clientLevelTeardownInProgress) throw new IllegalStateException(...)
        // 单机断线时正处在这个状态（level/player 还非空、teardown 已置位），而这一下
        // 是从 ClientTicks.tick() 冒出去的 —— 不接住就直接崩游戏。
        //
        // 【为什么还要把异常本身记下来】：它不是唯一一种。手机自己的收尾链
        // （{@code PhoneScreen.removed → shutdown}）与别的模组的 {@code ScreenEvent.Closing}
        // 监听器都挂在这条路上，它们抛出来的是真 bug（本仓的或别人的）。而这一句分不出是
        // 哪一种（{@code clientLevelTeardownInProgress} 是私有字段，读不到），所以不装作分得出：
        // 接住是为了别让玩家崩，连同栈一起写日志是为了不静默 —— 只记一句"世界正在卸载"
        // 会让真 bug 顶着这个名字从日志里消失，那正是本仓不允许的那种失败。
        p.closed = screen;
        try {
            mc.setScreen(null);
            p.stage = Stage.CLOSING;
        } catch (RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 遥控器：收起手机时 {} 抛出异常，这一次作废（队列已收回）",
                    e.getClass().getName(), e);
            giveUp(p, "界面关不掉：" + e.getClass().getSimpleName());
        }
    }

    /** 没有界面挡着了：清一次队列再注入，等模组把这一下取走 */
    private static void injectWithNoScreen(Pending p) {
        // 关界面的过程里可能又发生过一次 releaseAll（比如中间闪了一下别的界面），
        // 所以这里再收一次再注入，保证队列里恰好只有我们这一次
        HotkeyBackend.reset(p.mapping);
        HotkeyBackend.injectClick(p.mapping);
        p.stage = Stage.INJECTED_NO_GUI;
        p.age = 0;                          // 重新计时，否则总上限会把第二阶段掐掉
    }

    private static void succeed(Pending p) {
        pending = null;
        outcome = Outcome.OK;
        MCphone.LOGGER.info("[MCphone] 遥控器：{} → 模组取走了这一下", p.mapping.getName());
    }

    /** 放弃这一次。{@code why} 只进日志，玩家看到的是页面那句统一的话 */
    private static void giveUp(Pending p, String why) {
        // 放弃就要把队列收回来，否则那一次会迟到地响（见类注释）
        HotkeyBackend.reset(p.mapping);
        pending = null;
        outcome = Outcome.UNSUPPORTED;
        MCphone.LOGGER.info("[MCphone] 遥控器：{} → 没有模组消费（{}），手机触发不了这个键位",
                p.mapping.getName(), why);
    }
}
