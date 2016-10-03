/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.jdeps;

import static com.sun.tools.jdeps.Analyzer.Type.*;
import static com.sun.tools.jdeps.JdepsWriter.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.ResolutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation for the jdeps tool for static class dependency analysis.
 */
class JdepsTask {
    static interface BadArguments {
        String getKey();
        Object[] getArgs();
        boolean showUsage();
    }
    static class BadArgs extends Exception implements BadArguments {
        static final long serialVersionUID = 8765093759964640721L;
        BadArgs(String key, Object... args) {
            super(JdepsTask.getMessage(key, args));
            this.key = key;
            this.args = args;
        }

        BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }
        final String key;
        final Object[] args;
        boolean showUsage;

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public boolean showUsage() {
            return showUsage;
        }
    }

    static class UncheckedBadArgs extends RuntimeException implements BadArguments {
        static final long serialVersionUID = -1L;
        final BadArgs cause;
        UncheckedBadArgs(BadArgs cause) {
            super(cause);
            this.cause = cause;
        }
        @Override
        public String getKey() {
            return cause.key;
        }

        @Override
        public Object[] getArgs() {
            return cause.args;
        }

        @Override
        public boolean showUsage() {
            return cause.showUsage;
        }
    }

    static abstract class Option {
        Option(boolean hasArg, String... aliases) {
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        boolean isHidden() {
            return false;
        }

        boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt))
                    return true;
                if (hasArg && opt.startsWith(a + "="))
                    return true;
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(JdepsTask task, String opt, String arg) throws BadArgs;
        final boolean hasArg;
        final String[] aliases;
    }

    static abstract class HiddenOption extends Option {
        HiddenOption(boolean hasArg, String... aliases) {
            super(hasArg, aliases);
        }

        boolean isHidden() {
            return true;
        }
    }

    static Option[] recognizedOptions = {
        new Option(false, "-h", "-?", "-help", "--help") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.help = true;
            }
        },
        new Option(true, "-dotoutput") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Path p = Paths.get(arg);
                if (Files.exists(p) && (!Files.isDirectory(p) || !Files.isWritable(p))) {
                    throw new BadArgs("err.invalid.path", arg);
                }
                task.options.dotOutputDir = Paths.get(arg);;
            }
        },
        new Option(false, "-s", "-summary") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showSummary = true;
                task.options.verbose = SUMMARY;
            }
        },
        new Option(false, "-v", "-verbose",
                                "-verbose:module",
                                "-verbose:package",
                                "-verbose:class") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                switch (opt) {
                    case "-v":
                    case "-verbose":
                        task.options.verbose = VERBOSE;
                        task.options.filterSameArchive = false;
                        task.options.filterSamePackage = false;
                        break;
                    case "-verbose:module":
                        task.options.verbose = MODULE;
                        break;
                    case "-verbose:package":
                        task.options.verbose = PACKAGE;
                        break;
                    case "-verbose:class":
                        task.options.verbose = CLASS;
                        break;
                    default:
                        throw new BadArgs("err.invalid.arg.for.option", opt);
                }
            }
        },
        new Option(false, "-apionly") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.apiOnly = true;
            }
        },
        new Option(true, "--check") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Set<String> mods =  Set.of(arg.split(","));
                task.options.checkModuleDeps = mods;
                task.options.addmods.addAll(mods);
            }
        },
        new Option(true, "--gen-module-info") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Path p = Paths.get(arg);
                if (Files.exists(p) && (!Files.isDirectory(p) || !Files.isWritable(p))) {
                    throw new BadArgs("err.invalid.path", arg);
                }
                task.options.genModuleInfo = Paths.get(arg);
            }
        },
        new Option(false, "-jdkinternals") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.findJDKInternals = true;
                task.options.verbose = CLASS;
                if (task.options.includePattern == null) {
                    task.options.includePattern = Pattern.compile(".*");
                }
            }
        },

        // ---- paths option ----
        new Option(true, "-cp", "-classpath", "--class-path") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.classpath = arg;
            }
        },
        new Option(true, "--module-path") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.modulePath = arg;
            }
        },
        new Option(true, "--upgrade-module-path") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.upgradeModulePath = arg;
            }
        },
        new Option(true, "--system") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                if (arg.equals("none")) {
                    task.options.systemModulePath = null;
                } else {
                    Path path = Paths.get(arg);
                    if (Files.isRegularFile(path.resolve("lib").resolve("modules")))
                        task.options.systemModulePath = arg;
                    else
                        throw new BadArgs("err.invalid.path", arg);
                }
            }
        },
        new Option(true, "--add-modules") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                Set<String> mods = Set.of(arg.split(","));
                task.options.addmods.addAll(mods);
            }
        },
        new Option(true, "-m", "--module") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.rootModule = arg;
                task.options.addmods.add(arg);
            }
        },

        // ---- Target filtering options ----
        new Option(true, "-p", "-package") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.packageNames.add(arg);
            }
        },
        new Option(true, "-e", "-regex") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.regex = Pattern.compile(arg);
            }
        },
        new Option(true, "-requires") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.requires.add(arg);
            }
        },
        new Option(true, "-f", "-filter") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.filterRegex = Pattern.compile(arg);
            }
        },
        new Option(false, "-filter:package",
                          "-filter:archive", "-filter:module",
                          "-filter:none") {
            void process(JdepsTask task, String opt, String arg) {
                switch (opt) {
                    case "-filter:package":
                        task.options.filterSamePackage = true;
                        task.options.filterSameArchive = false;
                        break;
                    case "-filter:archive":
                    case "-filter:module":
                        task.options.filterSameArchive = true;
                        task.options.filterSamePackage = false;
                        break;
                    case "-filter:none":
                        task.options.filterSameArchive = false;
                        task.options.filterSamePackage = false;
                        break;
                }
            }
        },

        // ---- Source filtering options ----
        new Option(true, "-include") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.includePattern = Pattern.compile(arg);
            }
        },

        // Another alternative to list modules in --add-modules option
        new HiddenOption(true, "--include-system-modules") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.includeSystemModulePattern = Pattern.compile(arg);
            }
        },

        new Option(false, "-P", "-profile") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                task.options.showProfile = true;
            }
        },

        new Option(false, "-R", "-recursive") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.depth = 0;
                // turn off filtering
                task.options.filterSameArchive = false;
                task.options.filterSamePackage = false;
            }
        },

        new Option(false, "-I", "-inverse") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.inverse = true;
                // equivalent to the inverse of compile-time view analysis
                task.options.compileTimeView = true;
                task.options.filterSamePackage = true;
                task.options.filterSameArchive = true;
            }
        },

        new Option(false, "--compile-time") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.compileTimeView = true;
                task.options.filterSamePackage = true;
                task.options.filterSameArchive = true;
                task.options.depth = 0;
            }
        },

        new Option(false, "-q", "-quiet") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.nowarning = true;
            }
        },

        new Option(false, "-version") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.version = true;
            }
        },
        new HiddenOption(false, "-fullversion") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.fullVersion = true;
            }
        },
        new HiddenOption(false, "-showlabel") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showLabel = true;
            }
        },
        new HiddenOption(false, "--hide-show-module") {
            void process(JdepsTask task, String opt, String arg) {
                task.options.showModule = false;
            }
        },
        new HiddenOption(true, "-depth") {
            void process(JdepsTask task, String opt, String arg) throws BadArgs {
                try {
                    task.options.depth = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    throw new BadArgs("err.invalid.arg.for.option", opt);
                }
            }
        },
    };

    private static final String PROGNAME = "jdeps";
    private final Options options = new Options();
    private final List<String> inputArgs = new ArrayList<>();

    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
    }

    /**
     * Result codes.
     */
    static final int EXIT_OK = 0,       // Completed with no errors.
                     EXIT_ERROR = 1,    // Completed but reported errors.
                     EXIT_CMDERR = 2,   // Bad command-line arguments
                     EXIT_SYSERR = 3,   // System error or resource exhaustion.
                     EXIT_ABNORMAL = 4; // terminated abnormally

    int run(String... args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }
        try {
            handleOptions(args);
            if (options.help) {
                showHelp();
            }
            if (options.version || options.fullVersion) {
                showVersion(options.fullVersion);
            }
            if (!inputArgs.isEmpty() && options.rootModule != null) {
                reportError("err.invalid.arg.for.option", "-m");
            }
            if (inputArgs.isEmpty() && options.addmods.isEmpty() && options.includePattern == null
                    && options.includeSystemModulePattern == null && options.checkModuleDeps == null) {
                if (options.help || options.version || options.fullVersion) {
                    return EXIT_OK;
                } else {
                    showHelp();
                    return EXIT_CMDERR;
                }
            }
            if (options.genModuleInfo != null) {
                if (options.dotOutputDir != null || options.classpath != null || options.hasFilter()) {
                    showHelp();
                    return EXIT_CMDERR;
                }
            }

            if (options.numFilters() > 1) {
                reportError("err.invalid.filters");
                return EXIT_CMDERR;
            }

            if (options.inverse && options.depth != 1) {
                reportError("err.invalid.inverse.option", "-R");
                return EXIT_CMDERR;
            }

            if (options.inverse && options.numFilters() == 0) {
                reportError("err.invalid.filters");
                return EXIT_CMDERR;
            }

            if ((options.findJDKInternals) && (options.hasFilter() || options.showSummary)) {
                showHelp();
                return EXIT_CMDERR;
            }
            if (options.showSummary && options.verbose != SUMMARY) {
                showHelp();
                return EXIT_CMDERR;
            }
            if (options.checkModuleDeps != null && !inputArgs.isEmpty()) {
                reportError("err.invalid.module.option", inputArgs, "--check");
            }

            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs|UncheckedBadArgs e) {
            reportError(e.getKey(), e.getArgs());
            if (e.showUsage()) {
                log.println(getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (ResolutionException e) {
            reportError("err.exception.message", e.getMessage());
            return EXIT_CMDERR;
        } catch (IOException e) {
            e.printStackTrace();
            return EXIT_CMDERR;
        } finally {
            log.flush();
        }
    }

    boolean run() throws IOException {
        try (JdepsConfiguration config = buildConfig()) {

            // detect split packages
            config.splitPackages().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.format("split package: %s %s%n", e.getKey(),
                    e.getValue().toString()));

            // check if any module specified in -requires is missing
            Stream.concat(options.addmods.stream(), options.requires.stream())
                .filter(mn -> !config.isValidToken(mn))
                .forEach(mn -> config.findModule(mn).orElseThrow(() ->
                    new UncheckedBadArgs(new BadArgs("err.module.not.found", mn))));

            // --gen-module-info
            if (options.genModuleInfo != null) {
                return genModuleInfo(config);
            }

            // --check
            if (options.checkModuleDeps != null) {
                return new ModuleAnalyzer(config, log, options.checkModuleDeps).run();
            }

            if (options.dotOutputDir != null &&
                (options.verbose == SUMMARY || options.verbose == MODULE) &&
                !options.addmods.isEmpty() && inputArgs.isEmpty()) {
                return new ModuleAnalyzer(config, log).genDotFiles(options.dotOutputDir);
            }

            if (options.inverse) {
                return analyzeInverseDeps(config);
            } else {
                return analyzeDeps(config);
            }
        }
    }

    private JdepsConfiguration buildConfig() throws IOException {
        JdepsConfiguration.Builder builder =
            new JdepsConfiguration.Builder(options.systemModulePath);

        builder.upgradeModulePath(options.upgradeModulePath)
               .appModulePath(options.modulePath)
               .addmods(options.addmods);

        if (options.checkModuleDeps != null) {
            // check all system modules in the image
            builder.allModules();
        }

        if (options.classpath != null)
            builder.addClassPath(options.classpath);

        // build the root set of archives to be analyzed
        for (String s : inputArgs) {
            Path p = Paths.get(s);
            if (Files.exists(p)) {
                builder.addRoot(p);
            }
        }

        return builder.build();
    }

    private boolean analyzeDeps(JdepsConfiguration config) throws IOException {
        // output result
        final JdepsWriter writer;
        if (options.dotOutputDir != null) {
            writer = new DotFileWriter(options.dotOutputDir,
                                       options.verbose,
                                       options.showProfile,
                                       options.showModule,
                                       options.showLabel);
        } else {
            writer = new SimpleWriter(log,
                                      options.verbose,
                                      options.showProfile,
                                      options.showModule);
        }

        // analyze the dependencies
        DepsAnalyzer analyzer = new DepsAnalyzer(config,
                                        dependencyFilter(config),
                                        writer,
                                        options.verbose,
                                        options.apiOnly);

        boolean ok = analyzer.run(options.compileTimeView, options.depth);

        // print skipped entries, if any
        analyzer.archives()
            .forEach(archive -> archive.reader()
                .skippedEntries().stream()
                .forEach(name -> warning("warn.skipped.entry",
                                         name, archive.getPathName())));

        if (options.findJDKInternals && !options.nowarning) {
            Map<String, String> jdkInternals = new TreeMap<>();
            Set<String> deps = analyzer.dependences();
            // find the ones with replacement
            deps.forEach(cn -> replacementFor(cn).ifPresent(
                repl -> jdkInternals.put(cn, repl))
            );

            if (!deps.isEmpty()) {
                log.println();
                warning("warn.replace.useJDKInternals", getMessage("jdeps.wiki.url"));
            }

            if (!jdkInternals.isEmpty()) {
                log.println();
                log.format("%-40s %s%n", "JDK Internal API", "Suggested Replacement");
                log.format("%-40s %s%n", "----------------", "---------------------");
                jdkInternals.entrySet().stream()
                    .forEach(e -> {
                        String key = e.getKey();
                        String[] lines = e.getValue().split("\\n");
                        for (String s : lines) {
                            log.format("%-40s %s%n", key, s);
                            key = "";
                        }
                    });
            }
        }
        return ok;
    }

    private boolean analyzeInverseDeps(JdepsConfiguration config) throws IOException {
        JdepsWriter writer = new SimpleWriter(log,
                                              options.verbose,
                                              options.showProfile,
                                              options.showModule);

        InverseDepsAnalyzer analyzer = new InverseDepsAnalyzer(config,
                                                               dependencyFilter(config),
                                                               writer,
                                                               options.verbose,
                                                               options.apiOnly);
        boolean ok = analyzer.run();

        log.println();
        if (!options.requires.isEmpty())
            log.format("Inverse transitive dependences on %s%n", options.requires);
        else
            log.format("Inverse transitive dependences matching %s%n",
                options.regex != null
                    ? options.regex.toString()
                    : "packages " + options.packageNames);

        analyzer.inverseDependences().stream()
                .sorted(Comparator.comparing(this::sortPath))
                .forEach(path -> log.println(path.stream()
                                                .map(Archive::getName)
                                                .collect(Collectors.joining(" <- "))));
        return ok;
    }

    private String sortPath(Deque<Archive> path) {
        return path.peekFirst().getName();
    }

    private boolean genModuleInfo(JdepsConfiguration config) throws IOException {
        ModuleInfoBuilder builder
            = new ModuleInfoBuilder(config, inputArgs, options.genModuleInfo);
        boolean ok = builder.run();

        builder.modules().forEach(module -> {
            if (module.packages().contains("")) {
                reportError("ERROR: %s contains unnamed package.  " +
                    "module-info.java not generated%n", module.getPathName());
            }
        });

        if (!ok && !options.nowarning) {
            log.println("ERROR: missing dependencies");
            builder.visitMissingDeps(
                new Analyzer.Visitor() {
                    @Override
                    public void visitDependence(String origin, Archive originArchive,
                                                String target, Archive targetArchive) {
                        if (builder.notFound(targetArchive))
                            log.format("   %-50s -> %-50s %s%n",
                                origin, target, targetArchive.getName());
                    }
                });
        }
        return ok;
    }

    /**
     * Returns a filter used during dependency analysis
     */
    private JdepsFilter dependencyFilter(JdepsConfiguration config) {
        // Filter specified by -filter, -package, -regex, and -requires options
        JdepsFilter.Builder builder = new JdepsFilter.Builder();

        // source filters
        builder.includePattern(options.includePattern);
        builder.includeSystemModules(options.includeSystemModulePattern);

        builder.filter(options.filterSamePackage, options.filterSameArchive);
        builder.findJDKInternals(options.findJDKInternals);

        // -requires
        if (!options.requires.isEmpty()) {
            options.requires.stream()
                .forEach(mn -> {
                    Module m = config.findModule(mn).get();
                    builder.requires(mn, m.packages());
                });
        }
        // -regex
        if (options.regex != null)
            builder.regex(options.regex);
        // -package
        if (!options.packageNames.isEmpty())
            builder.packages(options.packageNames);
        // -filter
        if (options.filterRegex != null)
            builder.filter(options.filterRegex);

        // check if system module is set
        config.rootModules().stream()
            .map(Module::name)
            .forEach(builder::includeIfSystemModule);

        return builder.build();
    }

    public void handleOptions(String[] args) throws BadArgs {
        // process options
        for (int i=0; i < args.length; i++) {
            if (args[i].charAt(0) == '-') {
                String name = args[i];
                Option option = getOption(name);
                String param = null;
                if (option.hasArg) {
                    if (name.startsWith("-") && name.indexOf('=') > 0) {
                        param = name.substring(name.indexOf('=') + 1, name.length());
                    } else if (i + 1 < args.length) {
                        param = args[++i];
                    }
                    if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                        throw new BadArgs("err.missing.arg", name).showUsage(true);
                    }
                }
                option.process(this, name, param);
                if (option.ignoreRest()) {
                    i = args.length;
                }
            } else {
                // process rest of the input arguments
                for (; i < args.length; i++) {
                    String name = args[i];
                    if (name.charAt(0) == '-') {
                        throw new BadArgs("err.option.after.class", name).showUsage(true);
                    }
                    inputArgs.add(name);
                }
            }
        }
    }

    private Option getOption(String name) throws BadArgs {
        for (Option o : recognizedOptions) {
            if (o.matches(name)) {
                return o;
            }
        }
        throw new BadArgs("err.unknown.option", name).showUsage(true);
    }

    private void reportError(String key, Object... args) {
        log.println(getMessage("error.prefix") + " " + getMessage(key, args));
    }

    void warning(String key, Object... args) {
        log.println(getMessage("warn.prefix") + " " + getMessage(key, args));
    }

    private void showHelp() {
        log.println(getMessage("main.usage", PROGNAME));
        for (Option o : recognizedOptions) {
            String name = o.aliases[0].substring(1); // there must always be at least one name
            name = name.charAt(0) == '-' ? name.substring(1) : name;
            if (o.isHidden() || name.equals("h") || name.startsWith("filter:")) {
                continue;
            }
            log.println(getMessage("main.opt." + name));
        }
    }

    private void showVersion(boolean full) {
        log.println(version(full ? "full" : "release"));
    }

    private String version(String key) {
        // key=version:  mm.nn.oo[-milestone]
        // key=full:     mm.mm.oo[-milestone]-build
        if (ResourceBundleHelper.versionRB == null) {
            return System.getProperty("java.version");
        }
        try {
            return ResourceBundleHelper.versionRB.getString(key);
        } catch (MissingResourceException e) {
            return getMessage("version.unknown", System.getProperty("java.version"));
        }
    }

    static String getMessage(String key, Object... args) {
        try {
            return MessageFormat.format(ResourceBundleHelper.bundle.getString(key), args);
        } catch (MissingResourceException e) {
            throw new InternalError("Missing message: " + key);
        }
    }

    private static class Options {
        boolean help;
        boolean version;
        boolean fullVersion;
        boolean showProfile;
        boolean showModule = true;
        boolean showSummary;
        boolean apiOnly;
        boolean showLabel;
        boolean findJDKInternals;
        boolean nowarning = false;
        // default is to show package-level dependencies
        // and filter references from same package
        Analyzer.Type verbose = PACKAGE;
        boolean filterSamePackage = true;
        boolean filterSameArchive = false;
        Pattern filterRegex;
        Path dotOutputDir;
        Path genModuleInfo;
        String classpath;
        int depth = 1;
        Set<String> requires = new HashSet<>();
        Set<String> packageNames = new HashSet<>();
        Pattern regex;             // apply to the dependences
        Pattern includePattern;
        Pattern includeSystemModulePattern;
        boolean inverse = false;
        boolean compileTimeView = false;
        Set<String> checkModuleDeps;
        String systemModulePath = System.getProperty("java.home");
        String upgradeModulePath;
        String modulePath;
        String rootModule;
        Set<String> addmods = new HashSet<>();

        boolean hasFilter() {
            return numFilters() > 0;
        }

        int numFilters() {
            int count = 0;
            if (requires.size() > 0) count++;
            if (regex != null) count++;
            if (packageNames.size() > 0) count++;
            return count;
        }
    }

    private static class ResourceBundleHelper {
        static final ResourceBundle versionRB;
        static final ResourceBundle bundle;
        static final ResourceBundle jdkinternals;

        static {
            Locale locale = Locale.getDefault();
            try {
                bundle = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.jdeps", locale);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jdeps resource bundle for locale " + locale);
            }
            try {
                versionRB = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.version");
            } catch (MissingResourceException e) {
                throw new InternalError("version.resource.missing");
            }
            try {
                jdkinternals = ResourceBundle.getBundle("com.sun.tools.jdeps.resources.jdkinternals");
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find jdkinternals resource bundle");
            }
        }
    }

    /**
     * Returns the recommended replacement API for the given classname;
     * or return null if replacement API is not known.
     */
    private Optional<String> replacementFor(String cn) {
        String name = cn;
        String value = null;
        while (value == null && name != null) {
            try {
                value = ResourceBundleHelper.jdkinternals.getString(name);
            } catch (MissingResourceException e) {
                // go up one subpackage level
                int i = name.lastIndexOf('.');
                name = i > 0 ? name.substring(0, i) : null;
            }
        }
        return Optional.ofNullable(value);
    }
}
