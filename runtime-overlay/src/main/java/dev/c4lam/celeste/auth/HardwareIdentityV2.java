package dev.c4lam.celeste.auth;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Additive identity shared with native hardware_v2.rs; never changes legacy hashes. */
final class HardwareIdentityV2 {
    private HardwareIdentityV2() { }
    private static volatile String cached;
    static synchronized String current() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return "";
        if (cached == null) {
            try {
                String guid = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid", WinNT.KEY_READ | WinNT.KEY_WOW64_64KEY);
                cached = digest(cpu(), disk(), guid);
            } catch (RuntimeException error) { throw new IllegalStateException("Windows hardware identity could not be read", error); }
        }
        return cached;
    }
    static String digest(String cpu, String disk, String guid) {
        return Crypto.sha256(normalize(cpu) + ":" + normalize(disk) + ":" + normalize(guid));
    }
    private static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("Hardware identity unavailable");
        value = asciiLower(value.strip());
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 1024 || value.indexOf(':') >= 0
            || value.indexOf(0) >= 0 || value.contains("\r") || value.contains("\n")
            || List.of("unknown", "none", "default string", "to be filled by o.e.m.").contains(value))
            throw new IllegalArgumentException("Hardware identity unavailable");
        return value;
    }
    private static String asciiLower(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); result.append(c >= 'A' && c <= 'Z' ? (char)(c + 32) : c); }
        return result.toString();
    }
    private enum Processor { ProcessorId }
    private static String cpu() {
        int hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).intValue();
        try {
            if (hr < 0 && hr != 0x80010106) throw new IllegalStateException("COM unavailable");
            var values = new WbemcliUtil.WmiQuery<>("Win32_Processor", Processor.class).execute();
            TreeSet<String> ids = new TreeSet<>();
            for (int i = 0; i < values.getResultCount(); i++) {
                Object raw = values.getValue(Processor.ProcessorId, i);
                if (raw == null) continue;
                String id = asciiLower(raw.toString().strip());
                if (!id.matches("[0-9a-f]{16}") || id.equals("0000000000000000") || id.equals("ffffffffffffffff"))
                    throw new IllegalStateException("Processor identity unavailable");
                ids.add(id);
            }
            if (ids.isEmpty()) throw new IllegalStateException("Processor identity unavailable");
            return String.join("+", ids);
        } catch (RuntimeException error) {
            int count = SystemApi.INSTANCE.GetSystemFirmwareTable(0x52534d42, 0, null, 0);
            if (count < 8 || count > 4 * 1024 * 1024) throw new IllegalStateException("Firmware identity unavailable");
            try (Memory memory = new Memory(count)) {
                if (SystemApi.INSTANCE.GetSystemFirmwareTable(0x52534d42, 0, memory, count) != count)
                    throw new IllegalStateException("Firmware table changed");
                return String.join("+", firmwareIds(memory.getByteArray(0, count)));
            }
        } finally { if (hr == 0 || hr == 1) Ole32.INSTANCE.CoUninitialize(); }
    }
    static List<String> firmwareIds(byte[] raw) {
        if (raw.length < 8 || uint(raw, 4) != raw.length - 8) throw new IllegalArgumentException("Invalid firmware length");
        TreeSet<String> ids = new TreeSet<>();
        int offset = 8;
        while (offset + 4 <= raw.length) {
            int kind = raw[offset] & 255, size = raw[offset + 1] & 255;
            if (size < 4 || size > raw.length - offset) throw new IllegalArgumentException("Invalid firmware structure");
            if (kind == 4 && size >= 16) {
                long id = ByteBuffer.wrap(raw, offset + 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                if (id != 0 && id != -1) ids.add(HexFormat.of().toHexDigits(id));
            }
            int end = offset + size;
            while (end + 1 < raw.length && (raw[end] != 0 || raw[end + 1] != 0)) end++;
            if (end + 1 >= raw.length) throw new IllegalArgumentException("Invalid firmware strings");
            offset = end + 2;
            if (kind == 127) break;
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Processor identity unavailable");
        return List.copyOf(ids);
    }
    static String storageSerial(byte[] raw) {
        if (raw.length < 36) throw new IllegalArgumentException("Invalid storage descriptor");
        long declared = uint(raw, 4), start = uint(raw, 24);
        if (declared > raw.length || declared < 36 || start < 36 || start >= declared)
            throw new IllegalArgumentException("Invalid storage serial offset");
        int end = (int) start;
        while (end < declared && raw[end] != 0) { if (raw[end] < 0) throw new IllegalArgumentException("Nonascii storage serial"); end++; }
        if (end == declared || end == start || end - start > 256) throw new IllegalArgumentException("Invalid storage serial");
        String value = new String(raw, (int) start, end - (int)start, StandardCharsets.US_ASCII).strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty storage serial");
        return value;
    }
    private static long uint(byte[] raw, int offset) { return Integer.toUnsignedLong(ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static String disk() {
        char[] directory = new char[32768];
        int length = SystemApi.INSTANCE.GetWindowsDirectoryW(directory, directory.length);
        if (length < 3 || length >= directory.length || directory[1] != ':' || !((directory[0] >= 'A' && directory[0] <= 'Z') || (directory[0] >= 'a' && directory[0] <= 'z')))
            throw new IllegalStateException("Windows system drive unavailable");
        byte[] extents = query("\\\\.\\" + directory[0] + ":", 0x00560000, new byte[0]);
        if (extents.length < 32) throw new IllegalStateException("Invalid system disk extents");
        long count = uint(extents, 0);
        if (count < 1 || count > 32 || 8 + count * 24 > extents.length) throw new IllegalStateException("Invalid system disk count");
        TreeSet<String> serials = new TreeSet<>();
        for (int i = 0; i < count; i++) serials.add(storageSerial(query("\\\\.\\PhysicalDrive" + uint(extents, 8 + i * 24), 0x002d1400, new byte[12])));
        return String.join("+", serials);
    }
    private static byte[] query(String path, int code, byte[] input) {
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path, 0, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, null,
            WinNT.OPEN_EXISTING, 0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IllegalStateException("System storage device unavailable");
        try (Memory output = new Memory(65536); Memory in = input.length == 0 ? null : new Memory(input.length)) {
            if (in != null) in.write(0, input, 0, input.length);
            IntByReference count = new IntByReference();
            if (!SystemApi.INSTANCE.DeviceIoControl(handle, code, in, input.length, output, 65536, count, null)
                || count.getValue() < 0 || count.getValue() > 65536) throw new IllegalStateException("System storage query failed");
            return output.getByteArray(0, count.getValue());
        } finally { Kernel32.INSTANCE.CloseHandle(handle); }
    }
    private interface SystemApi extends StdCallLibrary {
        SystemApi INSTANCE = Native.load("kernel32", SystemApi.class, W32APIOptions.UNICODE_OPTIONS);
        int GetWindowsDirectoryW(char[] buffer, int size);
        int GetSystemFirmwareTable(int provider, int table, Pointer buffer, int size);
        boolean DeviceIoControl(WinNT.HANDLE handle, int code, Pointer input, int inputSize, Pointer output,
                              int outputSize, IntByReference returned, Pointer overlapped);
    }
}
