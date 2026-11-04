package chatsgate.whatsappfx.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// Windows DPAPI through the foreign function API: bytes protected for the current Windows user account. Only that account, on
/// this machine, can unprotect them again, so a copied key file is useless anywhere else. Loaded only on Windows ({@link KeyStore}).
final class Dpapi {

    private static final StructLayout BLOB = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbData"), MemoryLayout.paddingLayout(4), ValueLayout.ADDRESS.withName("pbData"));
    private static final int UI_FORBIDDEN = 1;
    private static final MethodHandle PROTECT, UNPROTECT, FREE;

    static {
        var linker = Linker.nativeLinker();
        var crypt = SymbolLookup.libraryLookup("crypt32", Arena.global());
        var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
        var fn = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        PROTECT = linker.downcallHandle(crypt.find("CryptProtectData").orElseThrow(), fn);
        UNPROTECT = linker.downcallHandle(crypt.find("CryptUnprotectData").orElseThrow(), fn);
        FREE = linker.downcallHandle(kernel.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    private Dpapi() { }

    /// `entropy` is not a secret: it keeps this program's blobs apart from other programs' blobs of the same user.
    static byte[] protect(byte[] plain, byte[] entropy) { return call(PROTECT, plain, entropy); }

    static byte[] unprotect(byte[] protectedBytes, byte[] entropy) { return call(UNPROTECT, protectedBytes, entropy); }

    private static byte[] call(MethodHandle h, byte[] in, byte[] entropy) {
        try (var arena = Arena.ofConfined()) {
            var inBlob = blob(arena, in);
            var entropyBlob = blob(arena, entropy);
            var out = arena.allocate(BLOB);
            int ok = (int) h.invoke(inBlob, MemorySegment.NULL, entropyBlob, MemorySegment.NULL, MemorySegment.NULL, UI_FORBIDDEN, out);
            if (ok == 0) throw new IllegalStateException("Windows could not protect or unprotect the session key for this user account");
            int n = out.get(ValueLayout.JAVA_INT, 0);
            var raw = out.get(ValueLayout.ADDRESS, 8);
            var data = raw.reinterpret(n);
            var result = data.toArray(ValueLayout.JAVA_BYTE);
            data.fill((byte) 0);
            var ignored = (MemorySegment) FREE.invoke(raw);
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Windows data protection failed", e);
        }
    }

    private static MemorySegment blob(Arena arena, byte[] data) {
        var seg = arena.allocate(BLOB);
        var buf = arena.allocate(Math.max(1, data.length));
        buf.copyFrom(MemorySegment.ofArray(data));
        seg.set(ValueLayout.JAVA_INT, 0, data.length);
        seg.set(ValueLayout.ADDRESS, 8, buf);
        return seg;
    }
}
