// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.tools;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;

/**
 * Reports where JOSM's widgets and map objects are on screen, as one line on
 * standard output, so an automated test can aim at them by name instead of by
 * hard-coded pixel coordinates.
 *
 * <p>This exists for benchmarking JOSM under two different server-side hosting
 * products (one process per user session, versus many tenants inside one JVM).
 * Both render JOSM off-screen and stream pixels to a browser canvas, so a test
 * driver has only pixels to work with: it must synthesise a click at a
 * coordinate and hope the layout is where it was when the coordinate was
 * written down. That is fragile, and worse, it fails <em>silently</em> — a
 * driver aiming at the wrong place looks exactly like an application that did
 * nothing. Publishing the real bounds removes the guesswork.</p>
 *
 * <p><b>Why standard output.</b> It is the one channel every host captures
 * identically, whether JOSM runs natively, inside a shared JVM, or in its own
 * per-session process. Writing to a file would work natively but requires
 * knowing where each host redirects a guest's filesystem, which differs per
 * product — and a measurement tool that is easier to read on one product than
 * another is not a fair instrument. One {@code println} of compact JSON is also
 * atomic enough not to interleave between tenants.</p>
 *
 * <p><b>Disabled unless asked for.</b> With {@code -Djosm.widgetbounds} unset
 * nothing is installed, no thread is started and no output is produced, so
 * behaviour is unchanged for every ordinary run.</p>
 *
 * Usage:
 * <pre>
 *   -Djosm.widgetbounds=once      dump once, when the main frame is up
 *   -Djosm.widgetbounds=2000      dump every 2000 ms (layout changes as panels move)
 * </pre>
 *
 * Output (single line, coordinates relative to the main window's top-left):
 * <pre>
 *   WIDGET-BOUNDS {"window":[1280,900],"widgets":{"map":[40,60,910,815],...},
 *                  "targets":{"building/9":[376,456],...}}
 * </pre>
 */
public final class WidgetBounds {

    /** Marker the test harness greps for. Deliberately unlikely to collide. */
    public static final String PREFIX = "WIDGET-BOUNDS ";

    private static final String PROPERTY = "josm.widgetbounds";

    /**
     * Components worth reporting, by simple class name → the logical name used
     * in the scenario. The toggle dialogs are here because a test verifies a
     * step by watching a region of the screen change, and those regions should
     * be derived from the real layout rather than copied out of a screenshot.
     */
    private static final Map<String, String> INTERESTING = Map.of(
            "MapView", "map",
            "LayerListDialog", "layers",
            "PropertiesDialog", "tags",
            "SelectionListDialog", "selection",
            "RelationListDialog", "relations");

    /**
     * Keeps one line to a sane size. When there are more objects than this, the
     * ones nearest the middle of the map win: truncating by id instead reported
     * whatever the data file happened to define first, which for a generated
     * grid meant a single row at the very edge of the view — technically on
     * screen, useless as a test target.
     */
    private static final int MAX_TARGETS = 80;

    private static volatile boolean installed;

    private WidgetBounds() {
        // static only
    }

    /**
     * Starts reporting if {@code -Djosm.widgetbounds} is set, otherwise returns
     * immediately. Safe to call more than once.
     */
    public static synchronized void install() {
        String mode = Utils.getSystemProperty(PROPERTY);
        if (mode == null || mode.isEmpty() || installed) {
            return;
        }
        installed = true;
        long interval = "once".equals(mode) ? 0 : parseInterval(mode);

        Thread t = new Thread(() -> run(interval), "widget-bounds-reporter");
        t.setDaemon(true);
        t.start();
    }

    private static long parseInterval(String mode) {
        try {
            return Math.max(0, Long.parseLong(mode.trim()));
        } catch (NumberFormatException e) {
            Logging.warn("widgetbounds: not a number: " + mode);
            return 0;
        }
    }

    private static void run(long interval) {
        // The frame exists before it is laid out, and a dump taken too early
        // reports zero-sized components — which is exactly the sort of quietly
        // wrong data this class is meant to eliminate. So wait for real
        // geometry rather than for a fixed delay.
        for (int i = 0; i < 600; i++) {
            if (ready()) {
                break;
            }
            sleep(250);
        }
        if (!ready()) {
            Logging.warn("widgetbounds: main frame never became ready");
            return;
        }
        do {
            try {
                dump();
            } catch (RuntimeException e) {
                Logging.warn("widgetbounds: " + e);
            }
            if (interval > 0) {
                sleep(interval);
            }
        } while (interval > 0);
    }

    private static boolean ready() {
        Component frame = MainApplication.getMainFrame();
        MapFrame map = MainApplication.getMap();
        return frame != null && frame.isShowing() && frame.getWidth() > 0
                && map != null && map.mapView != null
                && map.mapView.getWidth() > 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects on the EDT — component geometry is not safe to read off it. */
    private static void dump() {
        StringBuilder line = new StringBuilder(1024);
        Runnable collect = () -> line.append(PREFIX).append(build());
        if (EventQueue.isDispatchThread()) {
            collect.run();
        } else {
            try {
                EventQueue.invokeAndWait(collect);
            } catch (Exception e) {
                Logging.warn("widgetbounds: could not collect: " + e);
                return;
            }
        }
        // One write, so a line cannot interleave with another tenant's.
        System.out.println(line);
    }

    private static String build() {
        Component frame = MainApplication.getMainFrame();
        Map<String, int[]> widgets = new LinkedHashMap<>();
        collectWidgets(frame, frame, widgets);

        StringBuilder sb = new StringBuilder(1024);
        // The map's scale, so a test can assert that a zoom gesture did the same
        // amount of work in every channel. Verifying only that "the map changed"
        // is not enough: a wheel notch is not the same quantity as a wheel pixel
        // delta, so two channels can both pass a change check while zooming by
        // very different amounts — which would show up later as one product
        // allocating more than another.
        MapFrame mapFrame = MainApplication.getMap();
        double scale = mapFrame != null && mapFrame.mapView != null
                ? mapFrame.mapView.getScale()
                : 0;
        sb.append("{\"window\":[").append(frame.getWidth()).append(',')
                .append(frame.getHeight()).append("],\"scale\":").append(scale)
                .append(",\"widgets\":{");
        boolean first = true;
        for (Map.Entry<String, int[]> e : widgets.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            int[] b = e.getValue();
            sb.append('"').append(e.getKey()).append("\":[").append(b[0])
                    .append(',').append(b[1]).append(',').append(b[2])
                    .append(',').append(b[3]).append(']');
        }
        sb.append("},\"targets\":{");
        first = true;
        for (Map.Entry<String, int[]> e : collectTargets(frame).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":[")
                    .append(e.getValue()[0]).append(',')
                    .append(e.getValue()[1]).append(']');
        }
        return sb.append("}}").toString();
    }

    private static void collectWidgets(Component c, Component frame,
            Map<String, int[]> out) {
        String logical = INTERESTING.get(c.getClass().getSimpleName());
        if (logical != null && c.isShowing() && c.getWidth() > 0) {
            java.awt.Point p = SwingUtilities.convertPoint(c, 0, 0, frame);
            out.put(logical, new int[] {p.x, p.y, c.getWidth(), c.getHeight()});
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectWidgets(child, frame, out);
            }
        }
    }

    /**
     * Map objects as clickable points in window coordinates.
     *
     * <p>This is the part that makes a scenario portable across window sizes and
     * zoom levels: a test can ask for "the building numbered 9" instead of a
     * coordinate that was true once. Names come from the object's own tags, so
     * they mean something in the test script.</p>
     */
    private static Map<String, int[]> collectTargets(Component frame) {
        Map<String, int[]> out = new TreeMap<>();
        MapFrame map = MainApplication.getMap();
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (map == null || map.mapView == null || ds == null) {
            return out;
        }
        java.awt.Point origin = SwingUtilities.convertPoint(map.mapView, 0, 0,
                frame);
        Rectangle view = new Rectangle(0, 0, map.mapView.getWidth(),
                map.mapView.getHeight());

        double midX = view.getWidth() / 2;
        double midY = view.getHeight() / 2;
        List<double[]> found = new ArrayList<>();   // {distance, cx, cy, index}
        List<Way> ways = new ArrayList<>(ds.getWays());
        ways.sort((a, b) -> Long.compare(a.getUniqueId(), b.getUniqueId()));
        for (int i = 0; i < ways.size(); i++) {
            Way w = ways.get(i);
            if (w.isDeleted()) {
                continue;
            }
            double sx = 0;
            double sy = 0;
            int n = 0;
            for (Node node : w.getNodes()) {
                if (node.getCoor() == null) {
                    continue;
                }
                Point2D p = map.mapView.getPoint2D(node);
                sx += p.getX();
                sy += p.getY();
                n++;
            }
            if (n == 0) {
                continue;
            }
            double cx = sx / n;
            double cy = sy / n;
            // Only what is actually on screen can be clicked.
            if (!view.contains((int) Math.round(cx), (int) Math.round(cy))) {
                continue;
            }
            double dx = cx - midX;
            double dy = cy - midY;
            found.add(new double[] {dx * dx + dy * dy, cx, cy, i});
        }
        // Nearest the centre first, then by id so a tie is deterministic.
        found.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0])
                : Double.compare(a[3], b[3]));
        for (int k = 0; k < found.size() && k < MAX_TARGETS; k++) {
            double[] f = found.get(k);
            Way w = ways.get((int) f[3]);
            out.put(name(w), new int[] {origin.x + (int) Math.round(f[1]),
                    origin.y + (int) Math.round(f[2])});
        }
        return out;
    }

    private static String name(Way w) {
        String number = w.get("addr:housenumber");
        if (number != null) {
            return "building/" + number + "/" + w.getUniqueId();
        }
        String named = w.get("name");
        if (named != null) {
            return "way/" + named.replace('"', '_').replace(' ', '_');
        }
        return "way/" + w.getUniqueId();
    }
}
