/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.tools.jlink.internal;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.module.ResolvedModule;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import jdk.internal.module.ConfigurableModuleFinder;
import jdk.internal.module.ConfigurableModuleFinder.Phase;
import jdk.tools.jlink.internal.TaskHelper.BadArgs;
import static jdk.tools.jlink.internal.TaskHelper.JLINK_BUNDLE;
import jdk.tools.jlink.internal.TaskHelper.Option;
import jdk.tools.jlink.internal.TaskHelper.OptionsHelper;
import jdk.tools.jlink.internal.ImagePluginStack.ImageProvider;
import jdk.tools.jlink.Jlink.JlinkConfiguration;
import jdk.tools.jlink.Jlink.PluginsConfiguration;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.builder.DefaultImageBuilder;
import jdk.tools.jlink.plugin.Plugin;

/**
 * Implementation for the jlink tool.
 *
 * ## Should use jdk.joptsimple some day.
 */
public class JlinkTask {
    static final boolean DEBUG = Boolean.getBoolean("jlink.debug");

    private static <T extends Throwable> void fail(Class<T> type,
            String format,
            Object... args) throws T {
        String msg = new Formatter().format(format, args).toString();
        try {
            T t = type.getConstructor(String.class).newInstance(msg);
            throw t;
        } catch (InstantiationException |
                InvocationTargetException |
                NoSuchMethodException |
                IllegalAccessException e) {
            throw new InternalError("Unable to create an instance of " + type, e);
        }
    }

    private static final TaskHelper taskHelper
            = new TaskHelper(JLINK_BUNDLE);

    private static final Option<?>[] recognizedOptions = {
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.help = true;
        }, "--help", "-h"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            String[] dirs = arg.split(File.pathSeparator);
            int i = 0;
            Arrays.stream(dirs)
                  .map(Paths::get)
                  .forEach(task.options.modulePath::add);
        }, "--module-path", "-p"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                            "--limit-modules");
                }
                task.options.limitMods.add(mn);
            }
        }, "--limit-modules"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            for (String mn : arg.split(",")) {
                if (mn.isEmpty()) {
                    throw taskHelper.newBadArgs("err.mods.must.be.specified",
                            "--add-modules");
                }
                task.options.addMods.add(mn);
            }
        }, "--add-modules"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            Path path = Paths.get(arg);
            task.options.output = path;
        }, "--output"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            if ("little".equals(arg)) {
                task.options.endian = ByteOrder.LITTLE_ENDIAN;
            } else if ("big".equals(arg)) {
                task.options.endian = ByteOrder.BIG_ENDIAN;
            } else {
                throw taskHelper.newBadArgs("err.unknown.byte.order", arg);
            }
        }, "--endian"),
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.version = true;
        }, "--version"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            Path path = Paths.get(arg);
            if (Files.exists(path)) {
                throw taskHelper.newBadArgs("err.dir.exists", path);
            }
            task.options.packagedModulesPath = path;
        }, true, "--keep-packaged-modules"),
        new Option<JlinkTask>(true, (task, opt, arg) -> {
            task.options.saveoptsfile = arg;
        }, "--save-opts"),
        new Option<JlinkTask>(false, (task, opt, arg) -> {
            task.options.fullVersion = true;
        }, true, "--full-version"),};

    private static final String PROGNAME = "jlink";
    private final OptionsValues options = new OptionsValues();

    private static final OptionsHelper<JlinkTask> optionsHelper
            = taskHelper.newOptionsHelper(JlinkTask.class, recognizedOptions);
    private PrintWriter log;

    void setLog(PrintWriter out) {
        log = out;
        taskHelper.setLog(log);
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0, // Completed with no errors.
            EXIT_ERROR = 1, // Completed but reported errors.
            EXIT_CMDERR = 2, // Bad command-line arguments
            EXIT_SYSERR = 3, // System error or resource exhaustion.
            EXIT_ABNORMAL = 4;// terminated abnormally

    static class OptionsValues {
        boolean help;
        String  saveoptsfile;
        boolean version;
        boolean fullVersion;
        List<Path> modulePath = new ArrayList<>();
        Set<String> limitMods = new HashSet<>();
        Set<String> addMods = new HashSet<>();
        Path output;
        Path packagedModulesPath;
        ByteOrder endian = ByteOrder.nativeOrder();
    }

    int run(String[] args) {
        if (log == null) {
            setLog(new PrintWriter(System.out, true));
        }
        try {
            optionsHelper.handleOptions(this, args);
            if (options.help) {
                optionsHelper.showHelp(PROGNAME);
                return EXIT_OK;
            }
            if (optionsHelper.shouldListPlugins()) {
                optionsHelper.listPlugins();
                return EXIT_OK;
            }
            if (options.version || options.fullVersion) {
                taskHelper.showVersion(options.fullVersion);
                return EXIT_OK;
            }
            if (taskHelper.getExistingImage() == null) {
                if (options.modulePath == null || options.modulePath.isEmpty()) {
                    throw taskHelper.newBadArgs("err.modulepath.must.be.specified").showUsage(true);
                }
                createImage();
            } else {
                postProcessOnly(taskHelper.getExistingImage());
            }

            if (options.saveoptsfile != null) {
                Files.write(Paths.get(options.saveoptsfile), getSaveOpts().getBytes());
            }

            return EXIT_OK;
        } catch (UncheckedIOException | PluginException | IllegalArgumentException |
                 IOException | ResolutionException e) {
            log.println(taskHelper.getMessage("error.prefix") + " " + e.getMessage());
            if (DEBUG) {
                e.printStackTrace(log);
            }
            return EXIT_ERROR;
        } catch (BadArgs e) {
            taskHelper.reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            }
            if (DEBUG) {
                e.printStackTrace(log);
            }
            return EXIT_CMDERR;
        } catch (Throwable x) {
            log.println(taskHelper.getMessage("error.prefix") + " " + x.getMessage());
            x.printStackTrace(log);
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    /*
     * Jlink API entry point.
     */
    public static void createImage(JlinkConfiguration config,
                                   PluginsConfiguration plugins)
            throws Exception {
        Objects.requireNonNull(config);
        Objects.requireNonNull(config.getOutput());
        plugins = plugins == null ? new PluginsConfiguration() : plugins;

        if (config.getModulepaths().isEmpty()) {
            throw new Exception("Empty module paths");
        }

        ModuleFinder finder
                = newModuleFinder(config.getModulepaths(), config.getLimitmods(), config.getModules());

        // First create the image provider
        ImageProvider imageProvider
                = createImageProvider(finder,
                                      checkAddMods(config.getModules()),
                                      config.getLimitmods(),
                                      config.getByteOrder(),
                                      null);

        // Then create the Plugin Stack
        ImagePluginStack stack = ImagePluginConfiguration.parseConfiguration(plugins);

        //Ask the stack to proceed;
        stack.operate(imageProvider);
    }

    /*
     * Jlink API entry point.
     */
    public static void postProcessImage(ExecutableImage image, List<Plugin> postProcessorPlugins)
            throws Exception {
        Objects.requireNonNull(image);
        Objects.requireNonNull(postProcessorPlugins);
        PluginsConfiguration config = new PluginsConfiguration(postProcessorPlugins);
        ImagePluginStack stack = ImagePluginConfiguration.
                parseConfiguration(config);

        stack.operate((ImagePluginStack stack1) -> image);
    }

    private void postProcessOnly(Path existingImage) throws Exception {
        PluginsConfiguration config = taskHelper.getPluginsConfig(null);
        ExecutableImage img = DefaultImageBuilder.getExecutableImage(existingImage);
        if (img == null) {
            throw taskHelper.newBadArgs("err.existing.image.invalid");
        }
        postProcessImage(img, config.getPlugins());
    }

    private void createImage() throws Exception {
        if (options.output == null) {
            throw taskHelper.newBadArgs("err.output.must.be.specified").showUsage(true);
        }
        ModuleFinder finder
                = newModuleFinder(options.modulePath, options.limitMods, options.addMods);
        try {
            options.addMods = checkAddMods(options.addMods);
        } catch (IllegalArgumentException ex) {
            throw taskHelper.newBadArgs("err.mods.must.be.specified", "--add-modules")
                    .showUsage(true);
        }
        // First create the image provider
        ImageProvider imageProvider
                = createImageProvider(finder,
                        options.addMods,
                        options.limitMods,
                        options.endian,
                        options.packagedModulesPath);

        // Then create the Plugin Stack
        ImagePluginStack stack = ImagePluginConfiguration.
                parseConfiguration(taskHelper.getPluginsConfig(options.output));

        //Ask the stack to proceed
        stack.operate(imageProvider);
    }

    private static Set<String> checkAddMods(Set<String> addMods) {
        if (addMods.isEmpty()) {
            throw new IllegalArgumentException("no modules to add");
        }
        return addMods;
    }

    public static ModuleFinder newModuleFinder(List<Path> paths,
                                               Set<String> limitMods,
                                               Set<String> addMods)
    {
        ModuleFinder finder = ModuleFinder.of(paths.toArray(new Path[0]));

        // jmods are located at link-time
        if (finder instanceof ConfigurableModuleFinder) {
            ((ConfigurableModuleFinder) finder).configurePhase(Phase.LINK_TIME);
        }

        // if limitmods is specified then limit the universe
        if (!limitMods.isEmpty()) {
            finder = limitFinder(finder, limitMods, addMods);
        }
        return finder;
    }


    private static Path toPathLocation(ResolvedModule m) {
        Optional<URI> ouri = m.reference().location();
        if (!ouri.isPresent())
            throw new InternalError(m + " does not have a location");
        URI uri = ouri.get();
        return Paths.get(uri);
    }

    private static ImageProvider createImageProvider(ModuleFinder finder,
                                                     Set<String> addMods,
                                                     Set<String> limitMods,
                                                     ByteOrder order,
                                                     Path retainModulesPath)
            throws IOException
    {
        if (addMods.isEmpty()) {
            throw new IllegalArgumentException("empty modules and limitmods");
        }

        Configuration cf = Configuration.empty()
                .resolveRequires(finder,
                                 ModuleFinder.of(),
                                 addMods);

        Map<String, Path> mods = cf.modules().stream()
            .collect(Collectors.toMap(ResolvedModule::name, JlinkTask::toPathLocation));
        return new ImageHelper(cf, mods, order, retainModulesPath);
    }

    /**
     * Returns a ModuleFinder that limits observability to the given root
     * modules, their transitive dependences, plus a set of other modules.
     */
    private static ModuleFinder limitFinder(ModuleFinder finder,
            Set<String> roots,
            Set<String> otherMods) {

        // resolve all root modules
        Configuration cf = Configuration.empty()
                .resolveRequires(finder,
                                 ModuleFinder.of(),
                                 roots);

        // module name -> reference
        Map<String, ModuleReference> map = new HashMap<>();
        cf.modules().forEach(m -> {
            ModuleReference mref = m.reference();
            map.put(mref.descriptor().name(), mref);
        });

        // add the other modules
        otherMods.stream()
            .map(finder::find)
            .flatMap(Optional::stream)
            .forEach(mref -> map.putIfAbsent(mref.descriptor().name(), mref));

        // set of modules that are observable
        Set<ModuleReference> mrefs = new HashSet<>(map.values());

        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(map.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return mrefs;
            }
        };
    }

    private String getSaveOpts() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(new Date()).append("\n");
        for (String c : optionsHelper.getInputCommand()) {
            sb.append(c).append(" ");
        }

        return sb.toString();
    }

    private static String getBomHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(new Date()).append("\n");
        sb.append("#Please DO NOT Modify this file").append("\n");
        return sb.toString();
    }

    private String genBOMContent() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
        StringBuilder command = new StringBuilder();
        for (String c : optionsHelper.getInputCommand()) {
            command.append(c).append(" ");
        }
        sb.append("command").append(" = ").append(command);
        sb.append("\n");

        return sb.toString();
    }

    private static String genBOMContent(JlinkConfiguration config,
            PluginsConfiguration plugins)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(getBomHeader());
        sb.append(config);
        sb.append(plugins);
        return sb.toString();
    }

    private static class ImageHelper implements ImageProvider {

        final Set<Archive> archives;
        final ByteOrder order;
        final Path packagedModulesPath;

        ImageHelper(Configuration cf,
                    Map<String, Path> modsPaths,
                    ByteOrder order,
                    Path packagedModulesPath) throws IOException {
            archives = modsPaths.entrySet().stream()
                                .map(e -> newArchive(e.getKey(), e.getValue()))
                                .collect(Collectors.toSet());
            this.order = order;
            this.packagedModulesPath = packagedModulesPath;
        }

        private Archive newArchive(String module, Path path) {
            if (path.toString().endsWith(".jmod")) {
                return new JmodArchive(module, path);
            } else if (path.toString().endsWith(".jar")) {
                return new ModularJarArchive(module, path);
            } else if (Files.isDirectory(path)) {
                return new DirArchive(path);
            } else {
                fail(RuntimeException.class,
                        "Selected module %s (%s) not in jmod or modular jar format",
                        module,
                        path);
            }
            return null;
        }

        @Override
        public ExecutableImage retrieve(ImagePluginStack stack) throws IOException {
            ExecutableImage image = ImageFileCreator.create(archives, order, stack);
            if (packagedModulesPath != null) {
                // copy the packaged modules to the given path
                Files.createDirectories(packagedModulesPath);
                for (Archive a : archives) {
                    Path file = a.getPath();
                    Path dest = packagedModulesPath.resolve(file.getFileName());
                    Files.copy(file, dest);
                }
            }
            return image;
        }
    }

    private static enum Section {
        NATIVE_LIBS("native", nativeDir()),
        NATIVE_CMDS("bin", "bin"),
        CLASSES("classes", "classes"),
        CONFIG("conf", "conf"),
        UNKNOWN("unknown", "unknown");

        private static String nativeDir() {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return "bin";
            } else {
                return "lib";
            }
        }

        private final String jmodDir;
        private final String imageDir;

        Section(String jmodDir, String imageDir) {
            this.jmodDir = jmodDir;
            this.imageDir = imageDir;
        }

        String imageDir() {
            return imageDir;
        }

        String jmodDir() {
            return jmodDir;
        }

        boolean matches(String path) {
            return path.startsWith(jmodDir);
        }

        static Section getSectionFromName(String dir) {
            if (Section.NATIVE_LIBS.matches(dir)) {
                return Section.NATIVE_LIBS;
            } else if (Section.NATIVE_CMDS.matches(dir)) {
                return Section.NATIVE_CMDS;
            } else if (Section.CLASSES.matches(dir)) {
                return Section.CLASSES;
            } else if (Section.CONFIG.matches(dir)) {
                return Section.CONFIG;
            } else {
                return Section.UNKNOWN;
            }
        }
    }
}
