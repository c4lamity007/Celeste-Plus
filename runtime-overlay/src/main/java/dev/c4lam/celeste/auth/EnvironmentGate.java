package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Startup policy shared by the normal loader, Plus bootstrap A and runtime auth. */
public final class EnvironmentGate {
    private static final int OUTPUT_LIMIT = 16_384;
    private static final List<String> GUEST_MARKERS = List.of(
        "virtual machine", "vmware", "virtualbox", "vbox", "innotek",
        "hvm domu", "parallels", "openstack", "virtualmac"
    );

    private EnvironmentGate() { }

    /** Does not cache a successful check across installation or runtime lifetimes. */
    public static void requireAllowed() {
        String reason;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("windows")) reason = inspectWindows();
            else if (os.contains("linux")) reason = inspectLinux();
            else if (os.contains("mac")) reason = inspectMac();
            else reason = "environment_unavailable";
        } catch (RuntimeException failure) {
            // OS query diagnostics can contain local facts. Only export a stable reason.
            reason = "environment_unavailable";
        }
        reject(reason);
    }

    static void reject(String reason) {
        if (reason == null) return;
        String message = switch (reason) {
            case "environment_vm" -> "Celeste cannot run in a virtual machine.";
            case "environment_sandbox" -> "Celeste cannot run in a sandbox or container.";
            default -> "Celeste could not verify this computer's environment. Required host information is unavailable.";
        };
        throw new IllegalStateException(message + " (" + reason + ")");
    }

    private static String inspectWindows() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) return "environment_unavailable";
        Path shell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!shell.isAbsolute() || !Files.isRegularFile(shell)) return "environment_unavailable";
        // Bootstrap A has no game/JNA parent before downloading its signed support.
        // The fixed OS query therefore uses the inbox shell and examines THIS JVM's
        // token/modules, never the helper's token as a proxy for the target process.
        return windowsResult(command(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-EncodedCommand", Base64.getEncoder().encodeToString(
                windowsScript(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_16LE))));
    }

    static String windowsResult(Command result) {
        if (result == null || result.exit() != 0) return "environment_unavailable";
        String[] fields = result.output().strip().split("\\R", -1);
        if (fields.length != 4 || !fields[0].equals("celeste-environment-1")) return "environment_unavailable";
        try {
            String manufacturer = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            String model = new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            if (!fields[3].equals("0") && !fields[3].equals("1")) return "environment_unavailable";
            return windowsReason(manufacturer, model, fields[3].equals("1"));
        } catch (IllegalArgumentException invalid) {
            return "environment_unavailable";
        }
    }

    static String windowsReason(String manufacturer, String model, boolean sandbox) {
        if (sandbox) return "environment_sandbox";
        if (guestModel(manufacturer, model)) return "environment_vm";
        if (!meaningful(manufacturer) || !meaningful(model)) return "environment_unavailable";
        // HypervisorPresent is deliberately not a guest signal. Physical Windows
        // VBS and the Hyper-V root partition also report a running hypervisor.
        return null;
    }

    private static String inspectLinux() {
        boolean sandbox = Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))
            || Files.exists(Path.of("/.flatpak-info")) || meaningful(read(Path.of("/run/systemd/container")))
            || linuxSandbox(read(Path.of("/proc/self/cgroup")), read(Path.of("/proc/sys/kernel/osrelease")))
            || meaningful(System.getenv("container"));
        if (sandbox) return "environment_sandbox";
        if (guestModel(read(Path.of("/sys/class/dmi/id/sys_vendor")),
            read(Path.of("/sys/class/dmi/id/product_name")))) return "environment_vm";
        Path detector = Files.isExecutable(Path.of("/usr/bin/systemd-detect-virt"))
            ? Path.of("/usr/bin/systemd-detect-virt") : Path.of("/bin/systemd-detect-virt");
        String vm = detectionReason(command(detector.toString(), "--vm"), "environment_vm");
        if (vm != null && !vm.equals("environment_unavailable")) return vm;
        String container = detectionReason(command(detector.toString(), "--container"), "environment_sandbox");
        if (container != null && !container.equals("environment_unavailable")) return container;
        String chroot = namespaceReason(command(detector.toString(), "--chroot"));
        String users = namespaceReason(command(detector.toString(), "--private-users"));
        if ("environment_sandbox".equals(chroot) || "environment_sandbox".equals(users)) return "environment_sandbox";
        return vm != null || container != null || chroot != null || users != null ? "environment_unavailable" : null;
    }

    static String detectionReason(Command result, String detectedReason) {
        if (result == null) return "environment_unavailable";
        String value = result.output().strip().toLowerCase(Locale.ROOT);
        if (result.exit() == 1 && value.equals("none")) return null;
        if (result.exit() == 0 && value.matches("[a-z][a-z0-9_-]{0,63}") && !value.equals("none")) return detectedReason;
        return "environment_unavailable";
    }

    static boolean linuxSandbox(String cgroup, String kernel) {
        String groups = normalize(cgroup);
        String release = normalize(kernel);
        return release.contains("microsoft") || release.contains("wsl")
            || List.of("/docker/", "/docker-", "/kubepods", "/lxc/", "/libpod-", "/machine.slice/machine-", "/containerd/")
                .stream().anyMatch(groups::contains);
    }

    static String namespaceReason(Command result) {
        if (result == null || !result.output().isBlank()) return "environment_unavailable";
        return switch (result.exit()) {
            case 0 -> "environment_sandbox";
            case 1 -> null;
            default -> "environment_unavailable";
        };
    }

    private static String inspectMac() {
        if (meaningful(System.getenv("APP_SANDBOX_CONTAINER_ID"))) return "environment_sandbox";
        return macReason(command("/usr/sbin/sysctl", "-n", "hw.model"),
            command("/usr/sbin/sysctl", "-n", "kern.hv_vmm_present"));
    }

    static String macReason(Command model, Command guest) {
        if (model != null && model.exit() == 0 && guestModel(model.output())) return "environment_vm";
        if (guest != null && guest.exit() == 0 && guest.output().strip().equals("1")) return "environment_vm";
        if (model == null || model.exit() != 0 || !meaningful(model.output()) || guest == null || guest.exit() != 0
            || !guest.output().strip().equals("0")) return "environment_unavailable";
        return null;
    }

    static boolean guestModel(String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            String text = normalize(value);
            joined.append(text).append(' ');
            if (GUEST_MARKERS.stream().anyMatch(text::contains) || text.equals("virtualization")) return true;
            if (java.util.regex.Pattern.compile("(?:^|[^a-z0-9])(?:xen|kvm|qemu|bhyve|bochs)(?:[^a-z0-9]|$)").matcher(text).find()) return true;
        }
        String platform = joined.toString();
        return platform.contains("amazon ec2") && !platform.contains(".metal");
    }

    private static boolean meaningful(String value) {
        String text = normalize(value);
        return !text.isEmpty() && !List.of("unknown", "none", "default string", "not specified",
            "to be filled by o.e.m.", "system manufacturer", "system product name").contains(text);
    }

    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }

    private static String read(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(OUTPUT_LIMIT + 1);
            return bytes.length > OUTPUT_LIMIT ? null : new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException | SecurityException ignored) { return null; }
    }

    record Command(int exit, String output) { }

    static Command command(String... args) {
        Process process = null;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            Process started = process;
            AtomicReference<byte[]> output = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try { output.set(started.getInputStream().readNBytes(OUTPUT_LIMIT + 1)); }
                catch (IOException ignored) { /* Report unavailable, without OS diagnostics. */ }
            });
            if (!process.waitFor(10, TimeUnit.SECONDS)) return null;
            reader.join(1_000);
            byte[] bytes = output.get();
            if (bytes == null || bytes.length > OUTPUT_LIMIT) return null;
            return new Command(process.exitValue(), new String(bytes, StandardCharsets.UTF_8).strip());
        } catch (IOException | SecurityException ignored) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    static String windowsScript(long processId) {
        if (processId <= 0 || processId > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid process identity");
        return """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            try {
              Add-Type -TypeDefinition @'
            using System;
            using System.Diagnostics;
            using System.Runtime.InteropServices;
            public static class CelesteEnvironmentQuery {
              [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
              [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
              [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
              [DllImport("advapi32.dll", SetLastError=true)] static extern bool GetTokenInformation(IntPtr token, int kind, out int value, int size, out int needed);
              public static bool IsSandbox(int pid) {
                IntPtr process = OpenProcess(0x1000, false, pid), token = IntPtr.Zero;
                if (process == IntPtr.Zero) throw new InvalidOperationException();
                try {
                  if (!OpenProcessToken(process, 0x0008, out token)) throw new InvalidOperationException();
                  int value, needed;
                  if (!GetTokenInformation(token, 29, out value, 4, out needed) || needed != 4) throw new InvalidOperationException();
                  if (value != 0) return true;
                } finally { if (token != IntPtr.Zero) CloseHandle(token); CloseHandle(process); }
                using (Process target = Process.GetProcessById(pid)) {
                  foreach (ProcessModule module in target.Modules)
                    if (String.Equals(module.ModuleName, "SbieDll.dll", StringComparison.OrdinalIgnoreCase)) return true;
                }
                using (var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SYSTEM\\CurrentControlSet\\Control")) {
                  if (key == null) throw new InvalidOperationException();
                  foreach (string name in key.GetValueNames())
                    if (String.Equals(name, "ContainerType", StringComparison.OrdinalIgnoreCase)) {
                      if (key.GetValueKind(name) != Microsoft.Win32.RegistryValueKind.DWord) throw new InvalidOperationException();
                      return true;
                    }
                }
                return false;
              }
            }
            '@
              $facts = @(Get-CimInstance -ClassName Win32_ComputerSystem -Property Manufacturer,Model -OperationTimeoutSec 5)
              if ($facts.Count -ne 1) { exit 91 }
              $sandbox = [CelesteEnvironmentQuery]::IsSandbox(%d)
              [Console]::WriteLine('celeste-environment-1')
              [Console]::WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$facts[0].Manufacturer)))
              [Console]::WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$facts[0].Model)))
              [Console]::WriteLine([int]$sandbox)
            } catch { exit 91 }
            """.formatted(processId);
    }
}
