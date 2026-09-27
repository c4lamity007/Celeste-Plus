package dev.c4lam.celeste.auth;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record HardwareFingerprint(String system, String board, String processor, List<String> monitors) {
    private static final int REGISTRY_ACCESS = WinNT.KEY_READ | WinNT.KEY_WOW64_64KEY;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    private static final String DISPLAY_ROOT = "SYSTEM\\CurrentControlSet\\Enum\\DISPLAY";

    static HardwareFingerprint collect() {
        EnvironmentGate.requireAllowed();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return PortableHardwareFingerprint.collectLinux();
        }
        if (os.contains("mac")) {
            return PortableHardwareFingerprint.collectMac();
        }
        if (!os.contains("windows")) {
            throw new IllegalStateException("Celeste does not support this operating system");
        }
        try {
            return collectWindowsUsingWmi();
        } catch (RuntimeException unavailable) {
            return collectWindowsUsingFirmware();
        }
    }

    static HardwareFingerprint collectWindowsUsingWmi() {
        boolean releaseCom = initializeCom();
        try {
            return collectWindows();
        } finally {
            if (releaseCom) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    private static HardwareFingerprint collectWindows() {
        String systemValue = canonical(
            wmiFirst(ComputerSystemProperty.class, "Win32_ComputerSystem", ComputerSystemProperty.Manufacturer),
            wmiFirst(ComputerSystemProperty.class, "Win32_ComputerSystem", ComputerSystemProperty.Model),
            wmiFirst(BiosProperty.class, "Win32_BIOS", BiosProperty.SerialNumber),
            wmiFirst(ComputerProductProperty.class, "Win32_ComputerSystemProduct", ComputerProductProperty.UUID)
        );
        String boardValue = canonical(
            wmiFirst(BaseBoardProperty.class, "Win32_BaseBoard", BaseBoardProperty.Manufacturer),
            wmiFirst(BaseBoardProperty.class, "Win32_BaseBoard", BaseBoardProperty.Product),
            wmiFirst(BaseBoardProperty.class, "Win32_BaseBoard", BaseBoardProperty.Version),
            wmiFirst(BaseBoardProperty.class, "Win32_BaseBoard", BaseBoardProperty.SerialNumber)
        );
        String processorValue = canonical(
            wmiFirst(ProcessorProperty.class, "Win32_Processor", ProcessorProperty.ProcessorId),
            wmiFirst(ProcessorProperty.class, "Win32_Processor", ProcessorProperty.Manufacturer),
            wmiFirst(ProcessorProperty.class, "Win32_Processor", ProcessorProperty.Name)
        );

        return fromFields(systemValue, boardValue, processorValue);
    }

    static HardwareFingerprint collectWindowsUsingFirmware() {
        WindowsFirmwareIdentity.Identity firmware = WindowsFirmwareIdentity.read();
        String processorRoot = "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0";
        String manufacturer = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
            processorRoot, "VendorIdentifier", REGISTRY_ACCESS);
        String name = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
            processorRoot, "ProcessorNameString", REGISTRY_ACCESS);
        if (manufacturer == null || manufacturer.isBlank() || name == null || name.isBlank())
            throw new IllegalStateException("Windows processor identity is unavailable");
        return fromFields(canonical(firmware.system().toArray(String[]::new)),
            canonical(firmware.board().toArray(String[]::new)), canonical(firmware.processorId(), manufacturer, name));
    }

    private static HardwareFingerprint fromFields(String systemValue, String boardValue, String processorValue) {
        Set<String> monitorValues = new LinkedHashSet<>();
        for (byte[] edid : registryEdids()) {
            if (edid.length > 0) {
                monitorValues.add(Crypto.hex(Crypto.sha256(edid)));
            }
        }
        List<String> monitors = new ArrayList<>(monitorValues);
        monitors.sort(Comparator.naturalOrder());
        if (monitors.isEmpty()) {
            monitors.add(Crypto.sha256(systemValue));
        }
        return new HardwareFingerprint(
            Crypto.sha256(systemValue),
            Crypto.sha256(boardValue),
            Crypto.sha256(processorValue),
            List.copyOf(monitors)
        );
    }

    private static boolean initializeCom() {
        int status = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).intValue();
        if (status < 0 && status != RPC_E_CHANGED_MODE) {
            throw new IllegalStateException("Windows hardware services are unavailable");
        }
        Ole32.INSTANCE.CoInitializeSecurity(
            null,
            -1,
            null,
            null,
            Ole32.RPC_C_AUTHN_LEVEL_DEFAULT,
            Ole32.RPC_C_IMP_LEVEL_IMPERSONATE,
            null,
            Ole32.EOAC_NONE,
            null
        );
        return status == 0 || status == 1;
    }

    private static <T extends Enum<T>> String wmiFirst(Class<T> type, String wmiClass, T property) {
        try {
            WbemcliUtil.WmiResult<T> result = new WbemcliUtil.WmiQuery<>(wmiClass, type).execute();
            if (result.getResultCount() == 0) {
                return null;
            }
            Object value = result.getValue(property, 0);
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Windows hardware services are unavailable", exception);
        }
    }

    private static List<byte[]> registryEdids() {
        List<byte[]> values = new ArrayList<>();
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, DISPLAY_ROOT, REGISTRY_ACCESS)) {
            return values;
        }
        String[] vendors;
        try {
            vendors = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, DISPLAY_ROOT, REGISTRY_ACCESS);
        } catch (RuntimeException exception) {
            return values;
        }
        for (String vendor : vendors) {
            String vendorPath = DISPLAY_ROOT + "\\" + vendor;
            String[] instances;
            try {
                instances = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, vendorPath, REGISTRY_ACCESS);
            } catch (RuntimeException ignored) {
                continue;
            }
            for (String instance : instances) {
                String parameters = vendorPath + "\\" + instance + "\\Device Parameters";
                try {
                    if (!Advapi32Util.registryValueExists(
                        WinReg.HKEY_LOCAL_MACHINE, parameters, "EDID", REGISTRY_ACCESS
                    )) {
                        continue;
                    }
                    byte[] edid = Advapi32Util.registryGetBinaryValue(
                        WinReg.HKEY_LOCAL_MACHINE, parameters, "EDID", REGISTRY_ACCESS
                    );
                    if (edid != null && edid.length > 0) {
                        values.add(edid);
                    }
                } catch (RuntimeException ignored) {
                    // Native skips unreadable DISPLAY instances rather than failing closed here.
                }
            }
        }
        return values;
    }

    private static String canonical(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            result.append(normalized.isEmpty() ? "unknown" : normalized);
        }
        return result.toString();
    }

    private enum ComputerSystemProperty {
        Manufacturer,
        Model
    }

    private enum ComputerProductProperty {
        UUID
    }

    private enum BiosProperty {
        SerialNumber
    }

    private enum BaseBoardProperty {
        Manufacturer,
        Product,
        Version,
        SerialNumber
    }

    private enum ProcessorProperty {
        ProcessorId,
        Manufacturer,
        Name
    }
}
