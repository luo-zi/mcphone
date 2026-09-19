package com.november.mcphone.feature.hotkey.client;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * 「遥控器」在 1.21.1-neoforge 上的落点：把一次「按下」塞进某个 KeyMapping 的边沿队列。
 *
 * <h2>为什么是这个字段</h2>
 *
 * 模组消费键位的写法只有那么几种，其中覆盖最广的一种是：在自己的客户端 tick 里
 *
 * <pre>{@code
 * while (myKey.consumeClick()) { ...做事... }
 * }</pre>
 *
 * 而 {@code consumeClick()} 的全部实现就是「队列里有货就减一，然后说 true」
 * （{@code KeyMapping.consumeClick()}，它不判界面、也不判上下文）。所以往那个队列里
 * 放一次，模组自己那段轮询就会照常跑 —— 不需要反射它的类、不需要知道它做了什么。
 *
 * <h2>为什么按对象，不按键</h2>
 *
 * 原版公开的 {@code KeyMapping.click(InputConstants.Key)} 与 {@code KeyMapping.set(Key, boolean)}
 * 都是<b>按「键」寻址</b>的：它们走内部的 {@code KeyMappingLookup.getAll(key)}，把绑在
 * 那个键上的<b>全部</b> mapping 一起处理。这在你想「代替玩家按一下那个键」时是对的，
 * 在本功能里是错的，而且是两头的错：
 *
 * <ul>
 *   <li>一个键上被好几个模组分别绑了快捷键时（很常见），一次触发会把它们<b>全部</b>叫起来
 *       —— 那正是这个功能要解决的那种乱，不是它该制造出来的；</li>
 *   <li>玩家为了腾出键位而把模组的键位<b>解绑</b>之后，按「键」根本寻址不到它
 *       —— 而「腾出键位」正是这个功能的初衷。</li>
 * </ul>
 *
 * 所以这里要的是 {@code mc.options.keyMappings} 里那<b>一个</b>实例，直接动它自己的计数器。
 * 那个数组是公开的，对象拿得到；缺的只是读写入口，由本目标的
 * {@code META-INF/accesstransformer.cfg} 把 {@code clickCount} 放成 public 补上。
 *
 * <h2>为什么非要有 {@link #reset} 不可</h2>
 *
 * 因为「关掉界面会把队列清干净」这件事是<b>假的</b>。{@code KeyMapping.releaseAll()}
 * 在 {@code Minecraft.setScreen} 里被调用的那一句落在
 * {@code if (guiScreen != null)} 分支里 —— <b>只有开界面才清，关界面走的是 else 分支，
 * 什么都不清</b>（1.21.1 原版 {@code Minecraft.java}）。整个游戏里 {@code releaseAll()}
 * 只有那一个调用点。
 *
 * <p>所以「注入了一次、没人消费」这件事不会自己消失：它会留在队列里，等那个模组
 * 以后开始轮询时<b>迟到地响一次</b>。{@link KeyTrigger} 放弃一次触发、
 * 或者在收起手机之后重新注入之前，都必须自己把它收回 —— 就是 {@link #reset}。
 *
 * <h2>这里还有一件与「注入」无关的事</h2>
 *
 * {@link #contextOf}：读键位<b>自己声明</b>的生效场景（NeoForge 的 {@code KeyConflictContext}）。
 * 它有两个用处 —— 列表上那个小标签，以及"触发时要不要先收起手机"。之所以落在本类而不在别处：
 * "冲突上下文"这个概念只有这个目标有，共用侧只认 {@link HotkeyContext} 那几个值。
 *
 * <h2>为什么不是一个接口</h2>
 *
 * 这个类在三个平台上各有一份同名同签名的副本 —— 与 {@code platform.Slots}、
 * {@code platform.StackCodecs} 同一个套路。共用侧（{@code feature/hotkey/client/KeyTrigger}）
 * 直接 import 它，于是「三份签名必须一致」这件事由编译器保证：共用侧要在所有目标上
 * 都编得过。
 *
 * <p>三份的差别只有两处：<b>怎么把那个字段放开</b>，以及<b>有没有冲突上下文可读</b>。
 * 前者 neoforge 与 forge 用 {@code META-INF/accesstransformer.cfg}、fabric 用
 * {@code mcphone.accesswidener}；后者只有 neoforge / forge 有（本类这一支），
 * fabric 一律回 {@link HotkeyContext#ANY}。
 *
 * <p>注意两支 AT 的<b>条目写法不一样</b>：ModDevGradle 认 mojmap 名（本目录那份写的就是
 * {@code clickCount}），ForgeGradle 认 SRG 名（forge 那份写的是 {@code f_90818_}）。
 * 名字不同不是笔误，别顺手把它们统一了 —— 一统一就有一边开了个不存在的字段。
 */
public final class HotkeyBackend {

    private HotkeyBackend() {}

    /** 这个目标能不能真的注入。false 时「遥控器」App 不登记，主屏上看不到它 */
    public static boolean available() {
        return true;
    }

    /**
     * 按<b>对象</b>往边沿队列里放一次「按下」。
     *
     * <p>不按键寻址，所以绑在同一个键上的别的模组不受影响；也不管这个 mapping 当前绑的
     * 是哪个键、有没有被解绑 —— 字段就在对象上，与它绑了什么无关。
     */
    public static void injectClick(KeyMapping mapping) {
        mapping.clickCount++;
    }

    /**
     * 队列里还积着几次没被消费。注入之后隔一拍读这个数，就知道模组到底理没理这一下：
     * 归 0 ＝ 有人轮询到了；纹丝不动 ＝ 没人在看它（多半是它自己判了 {@code mc.screen == null}）。
     */
    public static int pending(KeyMapping mapping) {
        return mapping.clickCount;
    }

    /**
     * 把边沿队列清空 —— 收回我们注进去、还没人消费的那一次。
     *
     * <p>理由见类注释「为什么非要有 reset 不可」：原版只在<b>开</b>界面时清队列，
     * 关界面不清。不收的话，那一次会一直躺着，等模组以后轮询时迟到地响一下。
     *
     * <p>代价说清楚：如果玩家在点这一行的同一瞬间正好真按了那个键（队列里本来积着
     * 一次真按下），这一次会被一起抹掉。这个窗口是几十毫秒，而那是一个一年按不了
     * 几次的键位，所以按「抹掉」处理 —— 反过来（不做 reset）制造的是一个
     * <b>迟到的、玩家无从解释的</b>动作，比丢掉一次真按下糟得多。
     */
    public static void reset(KeyMapping mapping) {
        mapping.clickCount = 0;
    }

    /**
     * 这个键位声明自己在哪儿生效。
     *
     * <p>NeoForge 给每个 {@code KeyMapping} 配了一个 {@code IKeyConflictContext}，默认是
     * {@code UNIVERSAL}（原版那两种构造方法出来的键位都是它 —— 字段的初值就是它），
     * 模组可以在建键位时换成 {@code IN_GAME} / {@code GUI}，也可以实现一个自己的
     * —— 它是<b>接口</b>，不是枚举。
     *
     * <p>所以这里不猜"自己实现的那种是什么意思"，如实交给 {@link HotkeyContext#CUSTOM}：
     * 标签怎么写、触发时要不要先收起手机，都由那个枚举决定。
     */
    public static HotkeyContext contextOf(KeyMapping mapping) {
        IKeyConflictContext ctx = mapping.getKeyConflictContext();
        if (ctx == KeyConflictContext.IN_GAME) return HotkeyContext.WORLD;
        if (ctx == KeyConflictContext.GUI) return HotkeyContext.GUI;
        if (ctx == KeyConflictContext.UNIVERSAL) return HotkeyContext.ANY;
        // 自定义上下文（接口形式）如实标成"其它"；ctx 为 null 是病态用法
        // （setKeyConflictContext 不拦 null），当"没声明"处理 —— 不值得为它 NPE 掉整个列表的渲染。
        return ctx == null ? HotkeyContext.ANY : HotkeyContext.CUSTOM;
    }
}
