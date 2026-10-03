package dev.c4lam.celeste.auth;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Read-only fallback for the existing WMI fields; never persists hardware values. */
final class WindowsFirmwareIdentity {
    record Identity(List<String> system, List<String> board, String processorId) { }
    private WindowsFirmwareIdentity() { }

    private interface FirmwareApi extends StdCallLibrary {
        FirmwareApi INSTANCE = Native.load("kernel32", FirmwareApi.class);
        int GetSystemFirmwareTable(int provider, int table, Pointer output, int length);
    }

    static Identity read() {
        byte[] raw=readTable();
        try { return parse(raw); }
        finally { java.util.Arrays.fill(raw,(byte)0); }
    }

    /** Fresh request-owned SMBIOS bytes; the caller must wipe them after deriving its hashes. */
    static byte[] readTable() {
        int size = FirmwareApi.INSTANCE.GetSystemFirmwareTable(0x52534d42, 0, null, 0);
        if (size < 8 || size > 4 * 1024 * 1024) throw unavailable();
        try (Memory bytes = new Memory(size)) {
            try {
                if (FirmwareApi.INSTANCE.GetSystemFirmwareTable(0x52534d42, 0, bytes, size) != size)
                    throw unavailable();
                return bytes.getByteArray(0, size);
            } finally { bytes.clear(); }
        }
    }

    static Identity parse(byte[] raw) {
        if (raw == null || raw.length < 8 || raw.length > 4 * 1024 * 1024) throw unavailable();
        ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.getInt(4) != raw.length - 8) throw unavailable();
        List<String> system = null, board = null;
        String processor = null;
        for (int offset = 8; offset + 4 <= raw.length;) {
            int kind = raw[offset] & 255, size = raw[offset + 1] & 255;
            if (size < 4 || offset + size > raw.length) throw unavailable();
            int end = offset + size;
            while (end + 1 < raw.length && (raw[end] != 0 || raw[end + 1] != 0)) end++;
            if (end + 1 >= raw.length) throw unavailable();
            if (kind == 1 && system == null) {
                if (size < 24) throw unavailable();
                boolean nonzero = false, nonff = false;
                for (int i = offset + 8; i < offset + 24; i++) {
                    nonzero |= raw[i] != 0; nonff |= (raw[i] & 255) != 255;
                }
                if (!nonzero || !nonff) throw unavailable();
                boolean little = (raw[1] & 255) > 2 || ((raw[1] & 255) == 2 && (raw[2] & 255) >= 6);
                ByteBuffer uuid = ByteBuffer.wrap(raw, offset + 8, 16)
                    .order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                int a = uuid.getInt(); int b = uuid.getShort() & 65535, c = uuid.getShort() & 65535;
                StringBuilder tail = new StringBuilder();
                for (int i = offset + 18; i < offset + 24; i++) tail.append(String.format(Locale.ROOT, "%02x", raw[i] & 255));
                String id = String.format(Locale.ROOT, "%08x-%04x-%04x-%02x%02x-%s", a, b, c, raw[offset + 16] & 255, raw[offset + 17] & 255, tail);
                system = List.of(field(raw, offset, size, end, 4), field(raw, offset, size, end, 5), field(raw, offset, size, end, 7), id);
            } else if (kind == 2 && board == null) {
                board = List.of(field(raw, offset, size, end, 4), field(raw, offset, size, end, 5), field(raw, offset, size, end, 6), field(raw, offset, size, end, 7));
            } else if (kind == 4 && processor == null) {
                if (size < 16) throw unavailable();
                long id = bytes.getLong(offset + 8);
                if (id == 0 || id == -1) throw unavailable();
                processor = String.format(Locale.ROOT, "%016x", id);
            }
            offset = end + 2;
            if (kind == 127) break;
        }
        if (system == null || board == null || processor == null) throw unavailable();
        return new Identity(system, board, processor);
    }

    private static String field(byte[] raw, int offset, int size, int end, int position) {
        if (position >= size) throw unavailable();
        int index = raw[offset + position] & 255;
        if (index == 0) return "";
        int start = offset + size;
        for (int current = 1; current < index; current++) {
            while (start < end && raw[start] != 0) start++;
            if (start >= end) throw unavailable();
            start++;
        }
        int stop = start;
        while (stop < end && raw[stop] != 0) {
            if ((raw[stop] & 128) != 0) throw unavailable();
            stop++;
        }
        if (stop == start || stop - start > 1024) throw unavailable();
        return new String(raw, start, stop - start, StandardCharsets.US_ASCII);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Windows firmware identity is unavailable");
    }
}
