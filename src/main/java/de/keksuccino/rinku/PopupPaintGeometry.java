package de.keksuccino.rinku;

import java.awt.Rectangle;
import java.util.Objects;

/**
 * Separates popup callback-space retention from destination texture clipping.
 *
 * <p>CEF popup buffers always begin at popup-local coordinate {@code (0, 0)}, even when the popup itself extends
 * outside the browser view. The complete callback-space dirty region must still be retained because a later view
 * resize can expose pixels which were offscreen when they arrived. Only the optional texture upload is clipped to
 * the current browser texture.
 */
final class PopupPaintGeometry {
    private PopupPaintGeometry() {}

    static PaintPlan plan(Rectangle dirtySource, int sourceWidth, int sourceHeight, int destinationX, int destinationY, int destinationWidth, int destinationHeight) {
        Region retainedSource = clipSource(dirtySource, sourceWidth, sourceHeight);
        if (retainedSource == null) {
            return null;
        }

        Upload upload = clipUpload(retainedSource, destinationX, destinationY, destinationWidth, destinationHeight);
        boolean completeSourceFrame = retainedSource.x() == 0 && retainedSource.y() == 0 && retainedSource.width() == sourceWidth && retainedSource.height() == sourceHeight;
        return new PaintPlan(retainedSource, upload, completeSourceFrame);
    }

    private static Region clipSource(Rectangle dirtySource, int sourceWidth, int sourceHeight) {
        if (dirtySource == null || dirtySource.width <= 0 || dirtySource.height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }

        long left = Math.max(0L, dirtySource.x);
        long top = Math.max(0L, dirtySource.y);
        long right = Math.min((long) sourceWidth, (long) dirtySource.x + dirtySource.width);
        long bottom = Math.min((long) sourceHeight, (long) dirtySource.y + dirtySource.height);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Region((int) left, (int) top, (int) (right - left), (int) (bottom - top));
    }

    private static Upload clipUpload(Region retainedSource, int destinationX, int destinationY, int destinationWidth, int destinationHeight) {
        if (destinationWidth <= 0 || destinationHeight <= 0) {
            return null;
        }

        long unclippedLeft = (long) destinationX + retainedSource.x();
        long unclippedTop = (long) destinationY + retainedSource.y();
        long visibleLeft = Math.max(0L, unclippedLeft);
        long visibleTop = Math.max(0L, unclippedTop);
        long visibleRight = Math.min((long) destinationWidth, unclippedLeft + retainedSource.width());
        long visibleBottom = Math.min((long) destinationHeight, unclippedTop + retainedSource.height());
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            return null;
        }

        int width = (int) (visibleRight - visibleLeft);
        int height = (int) (visibleBottom - visibleTop);
        int sourceX = (int) (retainedSource.x() + visibleLeft - unclippedLeft);
        int sourceY = (int) (retainedSource.y() + visibleTop - unclippedTop);
        Region source = new Region(sourceX, sourceY, width, height);
        Region destination = new Region((int) visibleLeft, (int) visibleTop, width, height);
        return new Upload(source, destination);
    }

    static final class PaintPlan {
        private final Region retainedSource;
        private final Upload upload;
        private final boolean completeSourceFrame;

        PaintPlan(Region retainedSource, Upload upload, boolean completeSourceFrame) {
            this.retainedSource = retainedSource;
            this.upload = upload;
            this.completeSourceFrame = completeSourceFrame;
        }

        Region retainedSource() { return retainedSource; }
        Upload upload() { return upload; }
        boolean completeSourceFrame() { return completeSourceFrame; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PaintPlan that = (PaintPlan) o;
            return completeSourceFrame == that.completeSourceFrame &&
                    Objects.equals(retainedSource, that.retainedSource) &&
                    Objects.equals(upload, that.upload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(retainedSource, upload, completeSourceFrame);
        }
    }

    static final class Upload {
        private final Region source;
        private final Region destination;

        Upload(Region source, Region destination) {
            this.source = source;
            this.destination = destination;
        }

        Region source() { return source; }
        Region destination() { return destination; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Upload that = (Upload) o;
            return Objects.equals(source, that.source) &&
                    Objects.equals(destination, that.destination);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, destination);
        }
    }

    static final class Region {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        Region(int x, int y, int width, int height) {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("A paint region must have a non-negative origin and positive dimensions");
            }
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        int x() { return x; }
        int y() { return y; }
        int width() { return width; }
        int height() { return height; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Region region = (Region) o;
            return x == region.x && y == region.y && width == region.width && height == region.height;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, width, height);
        }
    }
}
