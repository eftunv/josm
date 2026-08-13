// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.preferences;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.Utils.getSystemProperty;

import java.awt.GraphicsEnvironment;
import java.io.File;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.PlatformManager;

/**
 * Class provides base directory locations for JOSM.
 * @since 13021
 */
public final class JosmBaseDirectories implements IBaseDirectories {

    private JosmBaseDirectories() {
        // hide constructor
    }

    private static final class InstanceHolder {
        static final JosmBaseDirectories INSTANCE = new JosmBaseDirectories();
    }

    /**
     * Returns the unique instance.
     * @return the unique instance
     */
    public static JosmBaseDirectories getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Internal storage for the preference directory.
     */
    private File preferencesDir;

    /**
     * Internal storage for the cache directory.
     */
    private File cacheDir;

    /**
     * Internal storage for the user data directory.
     */
    private File userdataDir;


    /**
     * Resolves a directory property, allowing a per-instance value.
     *
     * <p>JOSM assumes it owns the process: one instance, one set of directories,
     * named by one system property. A server-side host that runs several JOSM
     * instances inside a single JVM cannot honour that — every instance would
     * share one preferences, cache and autosave directory, so one user's state
     * would leak into another's, and a cache warmed by one would subsidise the
     * next. Setting the plain property per launch does not fix it either: it is
     * global, so instances starting concurrently race for one value.</p>
     *
     * <p>So a value may also be given per instance, keyed by the name of the
     * thread group the instance runs in: {@code josm.home.<group>} is preferred
     * over {@code josm.home}. Hosts that isolate instances in thread groups can
     * then give each one its own directory with no shared mutable state and no
     * ordering between launches. Ordinary single-instance runs are unaffected —
     * no such property exists, and the plain one is used exactly as before.</p>
     *
     * @param key the property, e.g. {@code josm.home}
     * @return the per-instance value if one is set, otherwise the plain value,
     *         otherwise {@code null}
     */
    private static String scopedProperty(String key) {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        // A host isolating instances gives each a root group of its own; walking
        // to the top finds it, and finds the JVM's own root otherwise.
        while (group != null && group.getParent() != null) {
            group = group.getParent();
        }
        if (group != null) {
            String scoped = getSystemProperty(key + '.' + group.getName());
            if (scoped != null && !scoped.isEmpty()) {
                return scoped;
            }
        }
        return getSystemProperty(key);
    }

    @Override
    public File getPreferencesDirectory(boolean createIfMissing) {
        if (preferencesDir == null) {
            String path = scopedProperty("josm.pref");
            if (path != null) {
                preferencesDir = new File(path).getAbsoluteFile();
            } else {
                path = scopedProperty("josm.home");
                if (path != null) {
                    preferencesDir = new File(path).getAbsoluteFile();
                } else {
                    preferencesDir = PlatformManager.getPlatform().getDefaultPrefDirectory();
                }
            }
        }
        try {
            if (createIfMissing && !preferencesDir.exists() && !preferencesDir.mkdirs()) {
                Logging.warn(tr("Failed to create missing preferences directory: {0}", preferencesDir.getAbsoluteFile()));
                if (!GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(
                            MainApplication.getMainFrame(),
                            tr("<html>Failed to create missing preferences directory: {0}</html>", preferencesDir.getAbsoluteFile()),
                            tr("Error"),
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        } catch (SecurityException e) {
            Logging.log(Logging.LEVEL_ERROR, "Unable to check if preferences dir must be created", e);
        }
        return preferencesDir;
    }

    @Override
    public File getUserDataDirectory(boolean createIfMissing) {
        if (userdataDir == null) {
            String path = scopedProperty("josm.userdata");
            if (path != null) {
                userdataDir = new File(path).getAbsoluteFile();
            } else {
                path = scopedProperty("josm.home");
                if (path != null) {
                    userdataDir = new File(path).getAbsoluteFile();
                } else {
                    userdataDir = PlatformManager.getPlatform().getDefaultUserDataDirectory();
                }
            }
        }
        try {
            if (createIfMissing && !userdataDir.exists() && !userdataDir.mkdirs()) {
                Logging.warn(tr("Failed to create missing user data directory: {0}", userdataDir.getAbsoluteFile()));
                if (!GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(
                            MainApplication.getMainFrame(),
                            tr("<html>Failed to create missing user data directory: {0}</html>", userdataDir.getAbsoluteFile()),
                            tr("Error"),
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        } catch (SecurityException e) {
            Logging.log(Logging.LEVEL_ERROR, "Unable to check if user data dir must be created", e);
        }
        return userdataDir;
    }

    @Override
    public File getCacheDirectory(boolean createIfMissing) {
        if (cacheDir == null) {
            String path = scopedProperty("josm.cache");
            if (path != null) {
                cacheDir = new File(path).getAbsoluteFile();
            } else {
                path = scopedProperty("josm.home");
                if (path != null) {
                    cacheDir = new File(path, "cache");
                } else {
                    path = Config.getPref().get("cache.folder", null);
                    if (path != null) {
                        cacheDir = new File(path).getAbsoluteFile();
                    } else {
                        cacheDir = PlatformManager.getPlatform().getDefaultCacheDirectory();
                    }
                }
            }
        }
        try {
            if (createIfMissing && !cacheDir.exists() && !cacheDir.mkdirs()) {
                Logging.warn(tr("Failed to create missing cache directory: {0}", cacheDir.getAbsoluteFile()));
                if (!GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(
                            MainApplication.getMainFrame(),
                            tr("<html>Failed to create missing cache directory: {0}</html>", cacheDir.getAbsoluteFile()),
                            tr("Error"),
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        } catch (SecurityException e) {
            Logging.log(Logging.LEVEL_ERROR, "Unable to check if cache dir must be created", e);
        }
        return cacheDir;
    }

    /**
     * Clears any previously calculated values used for {@link #getPreferencesDirectory(boolean)},
     * {@link #getCacheDirectory(boolean)} or {@link #getUserDataDirectory(boolean)}. Useful for tests.
     * @since 14052
     */
    public void clearMemos() {
        this.preferencesDir = null;
        this.cacheDir = null;
        this.userdataDir = null;
    }
}
