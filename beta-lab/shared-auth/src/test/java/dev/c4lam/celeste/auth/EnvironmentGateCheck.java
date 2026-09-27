package dev.c4lam.celeste.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

public final class EnvironmentGateCheck {
    private static int checks;
    private EnvironmentGateCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--probe")) {
            EnvironmentGate.requireAllowed();
            System.out.println("Current host environment allowed; no host identifiers recorded.");
            return;
        }
        if (args.length == 1 && args[0].equals("--oversized")) {
            System.out.print("x".repeat(20_000));
            return;
        }
        for (String[] machine : List.of(new String[] {"Dell Inc.", "XPS 15"},
            new String[] {"Microsoft Corporation", "Surface Laptop 6"},
            new String[] {"LENOVO", "ThinkPad T14"}, new String[] {"ASUSTeK COMPUTER INC.", "PRIME B650-PLUS"})) {
            same(null, EnvironmentGate.windowsReason(machine[0], machine[1], false), "physical Windows");
        }
        same(null, EnvironmentGate.windowsReason("XPG", "Xenia 15", false), "physical model containing Xen substring");
        same(null, EnvironmentGate.windowsReason("Amazon EC2", "c5.metal", false), "bare-metal EC2");
        same("environment_vm", EnvironmentGate.windowsReason("Amazon EC2", "c5.4xlarge", false), "EC2 guest");
        for (String[] guest : List.of(new String[] {"Microsoft Corporation", "Virtual Machine"},
            new String[] {"VMware, Inc.", "VMware Virtual Platform"}, new String[] {"innotek GmbH", "VirtualBox"},
            new String[] {"QEMU", "Standard PC (Q35 + ICH9, 2009)"}, new String[] {"Xen", "HVM domU"},
            new String[] {"Parallels", "Virtual Platform"}, new String[] {"Apple Inc.", "VirtualMac2,1"},
            new String[] {"bhyve", "BHYVE"}, new String[] {"Bochs", "Bochs"},
            new String[] {"OpenStack Foundation", "OpenStack Nova"}, new String[] {"Red Hat", "KVM"})) {
            same("environment_vm", EnvironmentGate.windowsReason(guest[0], guest[1], false), "VM guest");
        }
        same("environment_sandbox", EnvironmentGate.windowsReason("Dell", "XPS", true), "AppContainer/Sandboxie/container");
        for (String absent : new String[] {null, "", "unknown", "System Manufacturer"}) {
            same("environment_unavailable", EnvironmentGate.windowsReason(absent, "Physical", false), "missing manufacturer");
        }
        same("environment_unavailable", EnvironmentGate.windowsReason("Dell", "", false), "missing model");
        same(null, EnvironmentGate.windowsResult(windows("Microsoft Corporation", "Surface Pro 9", "0")), "parsed physical Windows");
        same("environment_vm", EnvironmentGate.windowsResult(windows("Microsoft Corporation", "Virtual Machine", "0")), "parsed Hyper-V guest");
        same("environment_sandbox", EnvironmentGate.windowsResult(windows("Dell", "XPS", "1")), "parsed sandbox");
        same("environment_unavailable", EnvironmentGate.windowsResult(windows("Dell", "XPS", "true")), "bad sandbox marker");
        same("environment_unavailable", EnvironmentGate.windowsResult(new EnvironmentGate.Command(91, "query error")), "Windows query failed");
        same("environment_unavailable", EnvironmentGate.windowsResult(new EnvironmentGate.Command(0, "celeste-environment-1\n!\n!\n0")), "bad facts encoding");
        same("environment_unavailable", EnvironmentGate.windowsResult(null), "Windows timeout");

        same(null, EnvironmentGate.detectionReason(command(1, "none\n"), "environment_vm"), "systemd physical");
        for (String vm : List.of("kvm", "vmware", "microsoft", "apple", "amazon", "xen", "other"))
            same("environment_vm", EnvironmentGate.detectionReason(command(0, vm), "environment_vm"), "systemd VM");
        for (String container : List.of("docker", "podman", "lxc", "wsl", "proot"))
            same("environment_sandbox", EnvironmentGate.detectionReason(command(0, container), "environment_sandbox"), "systemd container");
        for (EnvironmentGate.Command invalid : List.of(command(0, "none"), command(1, ""), command(2, "none"), command(0, "broken\noutput")))
            same("environment_unavailable", EnvironmentGate.detectionReason(invalid, "environment_vm"), "systemd failure");
        same("environment_unavailable", EnvironmentGate.detectionReason(null, "environment_vm"), "missing detector");
        same(null, EnvironmentGate.namespaceReason(command(1, "")), "no namespace isolation");
        same("environment_sandbox", EnvironmentGate.namespaceReason(command(0, "")), "namespace isolation");
        same("environment_unavailable", EnvironmentGate.namespaceReason(command(2, "")), "namespace failure");
        same("environment_unavailable", EnvironmentGate.namespaceReason(command(1, "permission denied")), "namespace error output");
        for (String group : List.of("0::/docker/123", "0::/kubepods.slice/test", "0::/user.slice/libpod-123.scope", "1:systemd:/lxc/test"))
            same(true, EnvironmentGate.linuxSandbox(group, "6.10.0"), "container cgroup");
        same(true, EnvironmentGate.linuxSandbox("0::/", "6.6.87.2-microsoft-standard-WSL2"), "WSL");
        same(false, EnvironmentGate.linuxSandbox("0::/user.slice/user-1000.slice/session-2.scope", "6.10.0"), "physical cgroup");
        same(null, EnvironmentGate.macReason(command(0, "Mac14,5"), command(0, "0")), "physical Apple silicon");
        same(null, EnvironmentGate.macReason(command(0, "MacBookPro16,1"), command(0, "0")), "physical Intel Mac");
        same("environment_vm", EnvironmentGate.macReason(command(0, "Mac14,5"), command(0, "1")), "Mac guest bit");
        same("environment_vm", EnvironmentGate.macReason(command(0, "VirtualMac2,1"), command(0, "0")), "Mac guest model");
        same("environment_unavailable", EnvironmentGate.macReason(command(0, "Mac14,5"), null), "missing Mac guest fact");
        same("environment_unavailable", EnvironmentGate.macReason(command(0, "Mac14,5"), command(0, "2")), "invalid Mac guest fact");
        same("environment_unavailable", EnvironmentGate.macReason(command(1, "permission denied"), command(0, "0")), "missing Mac model");

        for (String reason : List.of("environment_vm", "environment_sandbox", "environment_unavailable")) {
            try { EnvironmentGate.reject(reason); throw new AssertionError("environment gate did not stop startup"); }
            catch (IllegalStateException expected) {
                same(true, expected.getMessage().contains(reason), "stable rejection reason");
                same(false, expected.getMessage().contains("serial"), "no raw identity in failure");
            }
        }
        EnvironmentGate.reject(null);
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        same(null, EnvironmentGate.command(java, "-cp", System.getProperty("java.class.path"), EnvironmentGateCheck.class.getName(), "--oversized"), "bounded OS query output");
        same(null, EnvironmentGate.command(Path.of("build", "module-qa", "environment-gate", "missing-detector").toAbsolutePath().toString()), "missing OS query executable");
        System.out.println("Environment gate: " + checks + " checks passed.");
    }

    private static EnvironmentGate.Command windows(String manufacturer, String model, String flag) {
        return command(0, "celeste-environment-1\n" + encode(manufacturer) + "\n" + encode(model) + "\n" + flag);
    }
    private static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static EnvironmentGate.Command command(int exit, String output) { return new EnvironmentGate.Command(exit, output); }
    private static void same(Object expected, Object actual, String name) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
