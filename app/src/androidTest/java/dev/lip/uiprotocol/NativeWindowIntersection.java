package dev.lip.uiprotocol;

/** Pure translation-only observer proof. This does not select windows or authorize input. */
public final class NativeWindowIntersection {
    private NativeWindowIntersection() { }

    /** Integer edges, deliberately not an Android Rect or a mutable caller-owned array. */
    public static final class Bounds {
        public final int left, top, right, bottom;
        public Bounds(int left, int top, int right, int bottom) {
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Bounds)) return false;
            Bounds b=(Bounds)other;
            return left==b.left && top==b.top && right==b.right && bottom==b.bottom;
        }
        @Override public int hashCode() {
            int result=left; result=31*result+top; result=31*result+right; return 31*result+bottom;
        }
    }

    /** All observations must come from one fresh identified window/node observation.
     * IDs are window IDs, not view/node IDs. The caller independently selects and counts
     * IME windows; this helper cannot establish their source or freshness.
     */
    public static final class Input {
        public final Bounds nativeVisibleInWindow, axNodeInWindow, axNodeInScreen;
        public final Bounds axWindowInScreen, axRootInScreen;
        public final int nativeWindowId, axNodeWindowId, axWindowId, axRootWindowId;
        public final int matchingImeWindowCount, nativeDecorWidth, nativeDecorHeight;
        public Input(Bounds nativeVisibleInWindow, Bounds axNodeInWindow, Bounds axNodeInScreen,
                Bounds axWindowInScreen, Bounds axRootInScreen, int nativeWindowId,
                int axNodeWindowId, int axWindowId, int axRootWindowId,
                int matchingImeWindowCount, int nativeDecorWidth, int nativeDecorHeight) {
            this.nativeVisibleInWindow=nativeVisibleInWindow; this.axNodeInWindow=axNodeInWindow;
            this.axNodeInScreen=axNodeInScreen; this.axWindowInScreen=axWindowInScreen;
            this.axRootInScreen=axRootInScreen; this.nativeWindowId=nativeWindowId;
            this.axNodeWindowId=axNodeWindowId; this.axWindowId=axWindowId;
            this.axRootWindowId=axRootWindowId; this.matchingImeWindowCount=matchingImeWindowCount;
            this.nativeDecorWidth=nativeDecorWidth; this.nativeDecorHeight=nativeDecorHeight;
        }
    }

    private static boolean positive(Bounds b) {
        if (b==null) return false;
        long width=(long)b.right-b.left, height=(long)b.bottom-b.top;
        return width>0 && width<=Integer.MAX_VALUE && height>0 && height<=Integer.MAX_VALUE;
    }
    private static Integer translated(int edge, int origin) {
        long value=(long)edge+origin;
        return value<Integer.MIN_VALUE || value>Integer.MAX_VALUE ? null : (int)value;
    }

    /** Returns a fresh immutable projected native bound, or null on incomplete/inconsistent proof.
     * No tolerance, inferred offset, scale, skew, metrics-based origin or intersection fallback.
     * Equal rectangles alone cannot prove absence of every arbitrary transform: the caller
     * must also validate frame/transform metadata and corroborating fixed chrome observations.
     * Caller must retain its visibility/enabled checks, unchanged actual screen intersection,
     * and repeat this proof on a final fresh observation immediately before physical input.
     */
    public static Bounds project(Input input) {
        if (input==null || input.matchingImeWindowCount!=1 || input.nativeWindowId<0 ||
                input.axNodeWindowId<0 || input.axWindowId<0 || input.axRootWindowId<0 ||
                input.nativeWindowId!=input.axNodeWindowId || input.nativeWindowId!=input.axWindowId ||
                input.nativeWindowId!=input.axRootWindowId || input.nativeDecorWidth<=0 ||
                input.nativeDecorHeight<=0) return null;
        Bounds local=input.nativeVisibleInWindow, axLocal=input.axNodeInWindow;
        Bounds window=input.axWindowInScreen, root=input.axRootInScreen, screen=input.axNodeInScreen;
        if (!positive(local) || !positive(axLocal) || !positive(screen) || !positive(window) ||
                !positive(root) || !local.equals(axLocal) || !window.equals(root) ||
                (long)window.right-window.left!=input.nativeDecorWidth ||
                (long)window.bottom-window.top!=input.nativeDecorHeight ||
                local.left<0 || local.top<0 || local.right>input.nativeDecorWidth ||
                local.bottom>input.nativeDecorHeight) return null;
        Integer left=translated(local.left,window.left), top=translated(local.top,window.top);
        Integer right=translated(local.right,window.left), bottom=translated(local.bottom,window.top);
        if (left==null || top==null || right==null || bottom==null) return null;
        Bounds projected=new Bounds(left,top,right,bottom);
        return positive(projected) && projected.equals(screen) ? projected : null;
    }
}
