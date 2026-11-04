package chatsgate.whatsappfx.ui.notify;

import chatsgate.whatsappfx.core.Log;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The few user32 calls a notification card needs, through FFM: a window that appears without taking the keyboard from
 * whatever the person is typing in (WS_EX_NOACTIVATE), and that takes it once they click into it. Every call is
 * best-effort and never throws: on failure the card still shows, it just may take focus. Not Windows: all no-ops.
 */
final class Win {

    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    static final int GWL_EXSTYLE = -20;
    static final long WS_EX_NOACTIVATE = 0x08000000L, WS_EX_TOOLWINDOW = 0x80L;

    private static MethodHandle find, getLong, setLong, foreground, setForeground, threadOf, attach, currentThread;

    private Win() {}

    private static synchronized boolean load() {
        if (!WINDOWS) return false;
        if (find != null) return true;
        try {
            var linker = Linker.nativeLinker();
            var user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            var kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            find = linker.downcallHandle(user32.findOrThrow("FindWindowW"), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            getLong = linker.downcallHandle(user32.findOrThrow("GetWindowLongPtrW"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
            setLong = linker.downcallHandle(user32.findOrThrow("SetWindowLongPtrW"), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
            foreground = linker.downcallHandle(user32.findOrThrow("GetForegroundWindow"), FunctionDescriptor.of(ADDRESS));
            setForeground = linker.downcallHandle(user32.findOrThrow("SetForegroundWindow"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            threadOf = linker.downcallHandle(user32.findOrThrow("GetWindowThreadProcessId"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            attach = linker.downcallHandle(user32.findOrThrow("AttachThreadInput"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
            currentThread = linker.downcallHandle(kernel32.findOrThrow("GetCurrentThreadId"), FunctionDescriptor.of(JAVA_INT));
            return true;
        } catch (Throwable t) {
            Log.warn("notification window calls unavailable: " + t);
            find = null;
            return false;
        }
    }

    /**
     * This process's top-level window with exactly this title, or null. FindWindowW alone did not find the card window
     * (2026-10-27: it returned nothing while EnumWindows listed the window under that very title), so the card had no
     * WS_EX_NOACTIVATE, took activation on the first press, and its close button lost the click. EnumWindows over this
     * process's windows is the reliable route; FindWindowW stays as the quick first try.
     */
    static MemorySegment find(String title) {
        if (!load()) return null;
        try (var arena = Arena.ofConfined()) {
            var h = (MemorySegment) find.invoke(MemorySegment.NULL, arena.allocateFrom(title, StandardCharsets.UTF_16LE));
            if (!h.equals(MemorySegment.NULL)) return h;
        } catch (Throwable ignored) {
        }
        var found = enumerate(title);
        if (found == null) Log.warn("notification window '" + title + "' not found; it may take focus when it appears");
        return found;
    }

    private static MemorySegment enumerate(String title) {
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker();
            var user32 = SymbolLookup.libraryLookup("user32", arena);
            var enumWindows = linker.downcallHandle(user32.findOrThrow("EnumWindows"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            var getText = linker.downcallHandle(user32.findOrThrow("GetWindowTextW"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            var pidOf = linker.downcallHandle(user32.findOrThrow("GetWindowThreadProcessId"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            var me = ProcessHandle.current().pid();
            var result = new MemorySegment[1];
            var buf = arena.allocate(512);
            var pid = arena.allocate(JAVA_INT);
            var callback = java.lang.invoke.MethodHandles.lookup().findStatic(Win.class, "visit",
                    java.lang.invoke.MethodType.methodType(int.class, MemorySegment.class, long.class, Object[].class));
            var bound = java.lang.invoke.MethodHandles.insertArguments(callback, 2,
                    (Object) new Object[]{getText, pidOf, buf, pid, me, title, result});
            var stub = linker.upcallStub(bound, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), arena);
            var unused = (int) enumWindows.invoke(stub, 0L);
            return result[0];
        } catch (Throwable t) {
            Log.warn("window enumeration failed: " + t);
            return null;
        }
    }

    /** EnumWindows callback: keeps the first window of this process whose title matches; 0 stops the walk. */
    private static int visit(MemorySegment hwnd, long unusedParam, Object[] ctx) {
        try {
            var getText = (MethodHandle) ctx[0];
            var pidOf = (MethodHandle) ctx[1];
            var buf = (MemorySegment) ctx[2];
            var pid = (MemorySegment) ctx[3];
            var unused = (int) pidOf.invoke(hwnd, pid);
            if (pid.get(JAVA_INT, 0) != (long) ctx[4]) return 1;
            var n = (int) getText.invoke(hwnd, buf, 256);
            if (n <= 0) return 1;
            var text = new String(buf.asSlice(0, n * 2L).toArray(java.lang.foreign.ValueLayout.JAVA_CHAR));
            if (!text.equals(ctx[5])) return 1;
            ((MemorySegment[]) ctx[6])[0] = MemorySegment.ofAddress(hwnd.address());
            return 0;
        } catch (Throwable t) {
            return 1;
        }
    }

    static MemorySegment foreground() {
        if (!load()) return null;
        try {
            var h = (MemorySegment) foreground.invoke();
            return h.equals(MemorySegment.NULL) ? null : h;
        } catch (Throwable t) {
            return null;
        }
    }

    /** On: clicks and showing never activate it, and it has no taskbar button. Off: it can take the keyboard. */
    static void noActivate(MemorySegment hwnd, boolean on) {
        if (hwnd == null || !load()) return;
        try {
            var style = (long) getLong.invoke(hwnd, GWL_EXSTYLE);
            var next = on ? style | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW : style & ~WS_EX_NOACTIVATE;
            if (next != style) { var unused = (long) setLong.invoke(hwnd, GWL_EXSTYLE, next); }
        } catch (Throwable t) {
            Log.warn("notification window style not set: " + t);
        }
    }

    /** Gives the window back the keyboard it had (used once, after the card window is first made). */
    static void restore(MemorySegment hwnd) {
        if (hwnd == null || !load()) return;
        try {
            var unused = (int) setForeground.invoke(hwnd);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Takes the foreground for {@code hwnd}. Windows refuses SetForegroundWindow to a process that did not get the last
     * input, so the input queues are attached for the moment of the call (the documented way), then detached at once.
     * Called from the FX thread, which owns the window.
     */
    static void grab(MemorySegment hwnd) {
        if (hwnd == null || !load()) return;
        try {
            var fg = foreground();
            var mine = (int) currentThread.invoke();
            var theirs = fg == null ? 0 : (int) threadOf.invoke(fg, MemorySegment.NULL);
            var attached = theirs != 0 && theirs != mine && (int) attach.invoke(theirs, mine, 1) != 0;
            try {
                var unused = (int) setForeground.invoke(hwnd);
            } finally {
                if (attached) { var unused = (int) attach.invoke(theirs, mine, 0); }
            }
        } catch (Throwable t) {
            Log.warn("notification window could not take the keyboard: " + t);
        }
    }
}
