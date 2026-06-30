package io.github.hypercopy.clipboard.privileged;

import android.os.IBinder;

/**
 * v1.141.12 长驻会话模式：单次 app_process 进程持续复用，通过 stdin 逐条命令处理
 * block/restore，消除高频下反复冷启动 app_process 带来的延迟与状态竞态。
 *
 * 用法：
 *   app_process ... MiuiXmsfFirewallBinderCommand <uid> daemon
 *     启动后进入常驻循环，阻塞读 stdin（BufferedReader.readLine）：
 *       - 读到 "BLOCK"        -> 断网，回显 "READY"，等待下一条
 *       - 读到 "RESTORE"      -> 恢复网络，回显 "OK"
 *       - 读到 "EXIT" 或 EOF  -> 恢复网络并退出
 *
 * 兼容旧单次用法：<uid> block / <uid> restore / <uid> session。
 */
public final class MiuiXmsfFirewallBinderCommand {
    private static final int FIREWALL_CHAIN_OEM_DENY_3 = 9;
    private static final int FIREWALL_RULE_DEFAULT = 0;
    private static final int FIREWALL_RULE_DENY = 2;

    private MiuiXmsfFirewallBinderCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: MiuiXmsfFirewallBinderCommand <uid> <block|restore|session|daemon>");
        }

        int uid = Integer.parseInt(args[0]);
        Object connectivity = connectivityManager();
        String action = args[1];

        if ("session".equals(action)) {
            runSingleSession(connectivity, uid);
            return;
        }

        if ("daemon".equals(action)) {
            runDaemon(connectivity, uid);
            return;
        }

        boolean block = "block".equals(action);
        setUidNetworkBlocked(connectivity, uid, block);
    }

    /** 单次会话：断网 → READY → 阻塞等 stdin 输入(换行) → 恢复退出。兼容一次性回退方案。 */
    private static void runSingleSession(Object connectivity, int uid) {
        try {
            setUidNetworkBlocked(connectivity, uid, true);
            System.out.println("READY");
            System.out.flush();
            System.in.read();
        } catch (Throwable ignored) {
            // 任何异常/IO 中断都走到 finally 恢复网络
        } finally {
            runCatchingBlock(connectivity, uid, false);
        }
    }

    /** 常驻循环：阻塞读 stdin，按 BLOCK/RESTORE/EXIT 处理。恢复网络始终执行（含异常/EOF）。 */
    private static void runDaemon(Object connectivity, int uid) {
        // v1.141.14 修复时序 bug：启动后在进入主循环前必须先输出一次 READY，
        // 表示 daemon 已完全初始化（connectivity Manager/Binder 就绪）可接受命令。
        // Kotlin 端 startDaemon 会等待这条启动 READY 才算创建成功，消除"进程起来但内部未就绪"
        // 就立刻发 BLOCK 导致的竞态（此前每条 daemon started 后立刻 block failed）。
        System.out.println("READY");
        System.out.flush();
        java.io.BufferedReader reader =
                new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.equals("EXIT")) {
                    setUidNetworkBlocked(connectivity, uid, false);
                    System.out.println("OK");
                    System.out.flush();
                    break;
                } else if (cmd.equals("BLOCK")) {
                    setUidNetworkBlocked(connectivity, uid, true);
                    System.out.println("READY");
                    System.out.flush();
                } else if (cmd.equals("RESTORE")) {
                    setUidNetworkBlocked(connectivity, uid, false);
                    System.out.println("OK");
                    System.out.flush();
                }
                // 未知命令忽略
            }
        } catch (Throwable t) {
            // 保底：进程退出前恢复网络
            runCatchingBlock(connectivity, uid, false);
        } finally {
            runCatchingBlock(connectivity, uid, false);
        }
    }

    private static void runCatchingBlock(Object connectivity, int uid, boolean block) {
        try {
            setUidNetworkBlocked(connectivity, uid, block);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void setUidNetworkBlocked(Object connectivity, int uid, boolean block) throws Exception {
        connectivity.getClass()
                .getMethod("setFirewallChainEnabled", int.class, boolean.class)
                .invoke(connectivity, FIREWALL_CHAIN_OEM_DENY_3, true);

        connectivity.getClass()
                .getMethod("setUidFirewallRule", int.class, int.class, int.class)
                .invoke(
                        connectivity,
                        FIREWALL_CHAIN_OEM_DENY_3,
                        uid,
                        block ? FIREWALL_RULE_DENY : FIREWALL_RULE_DEFAULT
                );
    }

    private static Object connectivityManager() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "connectivity");
        if (binder == null) throw new IllegalStateException("connectivity service not found");

        return Class.forName("android.net.IConnectivityManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }
}