/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.jshell.tool;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jdk.internal.jshell.debug.InternalDebugControl;
import jdk.internal.jshell.tool.IOContext.InputInterruptedException;
import jdk.jshell.DeclarationSnippet;
import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.ExpressionSnippet;
import jdk.jshell.ImportSnippet;
import jdk.jshell.JShell;
import jdk.jshell.JShell.Subscription;
import jdk.jshell.MethodSnippet;
import jdk.jshell.PersistentSnippet;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.SourceCodeAnalysis.Suggestion;
import jdk.jshell.TypeDeclSnippet;
import jdk.jshell.UnresolvedReferenceException;
import jdk.jshell.VarSnippet;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.function.Supplier;
import jdk.internal.joptsimple.*;
import jdk.internal.jshell.tool.Feedback.FormatAction;
import jdk.internal.jshell.tool.Feedback.FormatCase;
import jdk.internal.jshell.tool.Feedback.FormatErrors;
import jdk.internal.jshell.tool.Feedback.FormatResolve;
import jdk.internal.jshell.tool.Feedback.FormatUnresolved;
import jdk.internal.jshell.tool.Feedback.FormatWhen;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static jdk.jshell.Snippet.SubKind.VAR_VALUE_SUBKIND;
import static java.util.stream.Collectors.toMap;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_COMPA;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_DEP;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_EVNT;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_FMGR;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_GEN;
import static jdk.internal.jshell.tool.ContinuousCompletionProvider.STARTSWITH_MATCHER;

/**
 * Command line REPL tool for Java using the JShell API.
 * @author Robert Field
 */
public class JShellTool implements MessageHandler {

    private static final String LINE_SEP = System.getProperty("line.separator");
    private static final Pattern LINEBREAK = Pattern.compile("\\R");
    private static final String RECORD_SEPARATOR = "\u241E";
    private static final String RB_NAME_PREFIX  = "jdk.internal.jshell.tool.resources";
    private static final String VERSION_RB_NAME = RB_NAME_PREFIX + ".version";
    private static final String L10N_RB_NAME    = RB_NAME_PREFIX + ".l10n";

    final InputStream cmdin;
    final PrintStream cmdout;
    final PrintStream cmderr;
    final PrintStream console;
    final InputStream userin;
    final PrintStream userout;
    final PrintStream usererr;
    final Preferences prefs;
    final Locale locale;

    final Feedback feedback = new Feedback();

    /**
     * The constructor for the tool (used by tool launch via main and by test
     * harnesses to capture ins and outs.
     * @param in command line input -- snippets, commands and user input
     * @param cmdout command line output, feedback including errors
     * @param cmderr start-up errors and debugging info
     * @param console console control interaction
     * @param userout code execution output  -- System.out.printf("hi")
     * @param usererr code execution error stream  -- System.err.printf("Oops")
     * @param prefs preferences to use
     * @param locale locale to use
     */
    public JShellTool(InputStream in, PrintStream cmdout, PrintStream cmderr,
            PrintStream console,
            PrintStream userout, PrintStream usererr,
            Preferences prefs, Locale locale) {
        this(in, cmdout, cmderr, console, null, userout, usererr, prefs, locale);
    }

    /**
     * The constructor for the tool (used by tool launch via main and by test
     * harnesses to capture ins and outs.
     * @param cmdin command line input -- snippets and commands
     * @param cmdout command line output, feedback including errors
     * @param cmderr start-up errors and debugging info
     * @param console console control interaction
     * @param userin code execution input, or null to use IOContext
     * @param userout code execution output  -- System.out.printf("hi")
     * @param usererr code execution error stream  -- System.err.printf("Oops")
     * @param prefs preferences to use
     * @param locale locale to use
     */
    public JShellTool(InputStream cmdin, PrintStream cmdout, PrintStream cmderr,
            PrintStream console,
            InputStream userin, PrintStream userout, PrintStream usererr,
            Preferences prefs, Locale locale) {
        this.cmdin = cmdin;
        this.cmdout = cmdout;
        this.cmderr = cmderr;
        this.console = console;
        this.userin = userin != null ? userin : new InputStream() {
            @Override
            public int read() throws IOException {
                return input.readUserInput();
            }
        };
        this.userout = userout;
        this.usererr = usererr;
        this.prefs = prefs;
        this.locale = locale;
    }

    private ResourceBundle versionRB = null;
    private ResourceBundle outputRB  = null;

    private IOContext input = null;
    private boolean regenerateOnDeath = true;
    private boolean live = false;
    private boolean feedbackInitialized = false;
    private String commandLineFeedbackMode = null;
    private List<String> remoteVMOptions = new ArrayList<>();

    SourceCodeAnalysis analysis;
    JShell state = null;
    Subscription shutdownSubscription = null;

    private boolean debug = false;
    public boolean testPrompt = false;
    private String cmdlineClasspath = null;
    private String startup = null;
    private String[] editor = null;
    private boolean editorWait = false;

    // Commands and snippets which should be replayed
    private List<String> replayableHistory;
    private List<String> replayableHistoryPrevious;

    static final String STARTUP_KEY  = "STARTUP";
    static final String EDITOR_KEY   = "EDITOR";
    static final String FEEDBACK_KEY = "FEEDBACK";
    static final String MODE_KEY     = "MODE";
    static final String REPLAY_RESTORE_KEY = "REPLAY_RESTORE";

    static final String DEFAULT_STARTUP =
            "\n" +
            "import java.util.*;\n" +
            "import java.io.*;\n" +
            "import java.math.*;\n" +
            "import java.net.*;\n" +
            "import java.util.concurrent.*;\n" +
            "import java.util.prefs.*;\n" +
            "import java.util.regex.*;\n" +
            "void printf(String format, Object... args) { System.out.printf(format, args); }\n";

    // Tool id (tid) mapping: the three name spaces
    NameSpace mainNamespace;
    NameSpace startNamespace;
    NameSpace errorNamespace;

    // Tool id (tid) mapping: the current name spaces
    NameSpace currentNameSpace;

    Map<Snippet,SnippetInfo> mapSnippet;

    /**
     * Is the input/output currently interactive
     *
     * @return true if console
     */
    boolean interactive() {
        return input != null && input.interactiveOutput();
    }

    void debug(String format, Object... args) {
        if (debug) {
            cmderr.printf(format + "\n", args);
        }
    }

    /**
     * Base output for command output -- no pre- or post-fix
     *
     * @param printf format
     * @param printf args
     */
    void rawout(String format, Object... args) {
        cmdout.printf(format, args);
    }

    /**
     * Must show command output
     *
     * @param format printf format
     * @param args printf args
     */
    void hard(String format, Object... args) {
        rawout(feedback.getPre() + format + feedback.getPost(), args);
    }

    /**
     * Error command output
     *
     * @param format printf format
     * @param args printf args
     */
    void error(String format, Object... args) {
        rawout(feedback.getErrorPre() + format + feedback.getErrorPost(), args);
    }

    /**
     * Optional output
     *
     * @param format printf format
     * @param args printf args
     */
    @Override
    public void fluff(String format, Object... args) {
        if (feedback.shouldDisplayCommandFluff() && interactive()) {
            hard(format, args);
        }
    }

    /**
     * Optional output -- with embedded per- and post-fix
     *
     * @param format printf format
     * @param args printf args
     */
    void fluffRaw(String format, Object... args) {
        if (feedback.shouldDisplayCommandFluff() && interactive()) {
            rawout(format, args);
        }
    }

    /**
     * Print using resource bundle look-up and adding prefix and postfix
     *
     * @param key the resource key
     */
    String getResourceString(String key) {
        if (outputRB == null) {
            try {
                outputRB = ResourceBundle.getBundle(L10N_RB_NAME, locale);
            } catch (MissingResourceException mre) {
                error("Cannot find ResourceBundle: %s for locale: %s", L10N_RB_NAME, locale);
                return "";
            }
        }
        String s;
        try {
            s = outputRB.getString(key);
        } catch (MissingResourceException mre) {
            error("Missing resource: %s in %s", key, L10N_RB_NAME);
            return "";
        }
        return s;
    }

    /**
     * Add prefixing to embedded newlines in a string, leading with the normal
     * prefix
     *
     * @param s the string to prefix
     */
    String prefix(String s) {
        return prefix(s, feedback.getPre());
    }

    /**
     * Add prefixing to embedded newlines in a string
     *
     * @param s the string to prefix
     * @param leading the string to prepend
     */
    String prefix(String s, String leading) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return leading
                + s.substring(0, s.length() - 1).replaceAll("\\R", System.getProperty("line.separator") + feedback.getPre())
                + s.substring(s.length() - 1, s.length());
    }

    /**
     * Print using resource bundle look-up and adding prefix and postfix
     *
     * @param key the resource key
     */
    void hardrb(String key) {
        String s = prefix(getResourceString(key));
        cmdout.println(s);
    }

    /**
     * Format using resource bundle look-up using MessageFormat
     *
     * @param key the resource key
     * @param args
     */
    String messageFormat(String key, Object... args) {
        String rs = getResourceString(key);
        return MessageFormat.format(rs, args);
    }

    /**
     * Print using resource bundle look-up, MessageFormat, and add prefix and
     * postfix
     *
     * @param key the resource key
     * @param args
     */
    void hardmsg(String key, Object... args) {
        cmdout.println(prefix(messageFormat(key, args)));
    }

    /**
     * Print error using resource bundle look-up, MessageFormat, and add prefix
     * and postfix
     *
     * @param key the resource key
     * @param args
     */
    @Override
    public void errormsg(String key, Object... args) {
        if (isRunningInteractive()) {
            cmdout.println(prefix(messageFormat(key, args), feedback.getErrorPre()));
        } else {
            startmsg(key, args);
        }
    }

    /**
     * Print command-line error using resource bundle look-up, MessageFormat
     *
     * @param key the resource key
     * @param args
     */
    void startmsg(String key, Object... args) {
        cmderr.println(prefix(messageFormat(key, args), ""));
    }

    /**
     * Print (fluff) using resource bundle look-up, MessageFormat, and add
     * prefix and postfix
     *
     * @param key the resource key
     * @param args
     */
    @Override
    public void fluffmsg(String key, Object... args) {
        if (feedback.shouldDisplayCommandFluff() && interactive()) {
            hardmsg(key, args);
        }
    }

    <T> void hardPairs(Stream<T> stream, Function<T, String> a, Function<T, String> b) {
        Map<String, String> a2b = stream.collect(toMap(a, b,
                (m1, m2) -> m1,
                () -> new LinkedHashMap<>()));
        int aLen = 0;
        for (String av : a2b.keySet()) {
            aLen = Math.max(aLen, av.length());
        }
        String format = "   %-" + aLen + "s -- %s";
        String indentedNewLine = LINE_SEP + feedback.getPre()
                + String.format("   %-" + (aLen + 4) + "s", "");
        for (Entry<String, String> e : a2b.entrySet()) {
            hard(format, e.getKey(), e.getValue().replaceAll("\n", indentedNewLine));
        }
    }

    /**
     * Trim whitespace off end of string
     *
     * @param s
     * @return
     */
    static String trimEnd(String s) {
        int last = s.length() - 1;
        int i = last;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            --i;
        }
        if (i != last) {
            return s.substring(0, i + 1);
        } else {
            return s;
        }
    }

    /**
     * Normal start entry point
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        new JShellTool(System.in, System.out, System.err, System.out,
                 System.out, System.err,
                 Preferences.userRoot().node("tool/JShell"),
                 Locale.getDefault())
                .start(args);
    }

    public void start(String[] args) throws Exception {
        List<String> loadList = processCommandArgs(args);
        if (loadList == null) {
            // Abort
            return;
        }
        try (IOContext in = new ConsoleIOContext(this, cmdin, console)) {
            start(in, loadList);
        }
    }

    private void start(IOContext in, List<String> loadList) {
        // If startup hasn't been set by command line, set from retained/default
        if (startup == null) {
            startup = prefs.get(STARTUP_KEY, null);
            if (startup == null) {
                startup = DEFAULT_STARTUP;
            }
        }

        // Read retained editor setting (if any)
        String editorString = prefs.get(EDITOR_KEY, "");
        if (editorString == null || editorString.isEmpty()) {
            editor = null;
        } else {
            char waitMarker = editorString.charAt(0);
            if (waitMarker == '-' || waitMarker == '*') {
                editorWait = waitMarker == '-';
                editorString = editorString.substring(1);
            }
            editor = editorString.split(RECORD_SEPARATOR);
        }

        resetState(); // Initialize

        // Read replay history from last jshell session into previous history
        String prevReplay = prefs.get(REPLAY_RESTORE_KEY, null);
        if (prevReplay != null) {
            replayableHistoryPrevious = Arrays.asList(prevReplay.split(RECORD_SEPARATOR));
        }

        for (String loadFile : loadList) {
            runFile(loadFile, "jshell");
        }

        if (regenerateOnDeath) {
            hardmsg("jshell.msg.welcome", version());
        }

        try {
            while (regenerateOnDeath) {
                if (!live) {
                    resetState();
                }
                run(in);
            }
        } finally {
            closeState();
        }
    }

    /**
     * Process the command line arguments.
     * Set options.
     * @param args the command line arguments
     * @return the list of files to be loaded
     */
    private List<String> processCommandArgs(String[] args) {
        OptionParser parser = new OptionParser();
        OptionSpec<String> cp = parser.accepts("class-path").withRequiredArg();
        OptionSpec<String> st = parser.accepts("startup").withRequiredArg();
        parser.acceptsAll(asList("n", "no-startup"));
        OptionSpec<String> fb = parser.accepts("feedback").withRequiredArg();
        parser.accepts("q");
        parser.accepts("s");
        parser.accepts("v");
        OptionSpec<String> r = parser.accepts("R").withRequiredArg();
        parser.acceptsAll(asList("h", "help"));
        parser.accepts("version");
        parser.accepts("full-version");
        NonOptionArgumentSpec<String> loadFileSpec = parser.nonOptions();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            if (ex.options().isEmpty()) {
                startmsg("jshell.err.opt.invalid", stream(args).collect(joining(", ")));
            } else {
                boolean isKnown = parser.recognizedOptions().containsKey(ex.options().iterator().next());
                startmsg(isKnown
                        ? "jshell.err.opt.arg"
                        : "jshell.err.opt.unknown",
                        ex.options()
                        .stream()
                        .collect(joining(", ")));
            }
            return null;
        }

        if (options.has("help")) {
            printUsage();
            return null;
        }
        if (options.has("version")) {
            cmdout.printf("jshell %s\n", version());
            return null;
        }
        if (options.has("full-version")) {
            cmdout.printf("jshell %s\n", fullVersion());
            return null;
        }
        if (options.has(cp)) {
            List<String> cps = options.valuesOf(cp);
            if (cps.size() > 1) {
                startmsg("jshell.err.opt.one", "--class-path");
                return null;
            }
            cmdlineClasspath = cps.get(0);
        }
        if (options.has(st)) {
            List<String> sts = options.valuesOf(st);
            if (sts.size() != 1 || options.has("no-startup")) {
                startmsg("jshell.err.opt.startup.one");
                return null;
            }
            startup = readFile(sts.get(0), "--startup");
            if (startup == null) {
                return null;
            }
        } else if (options.has("no-startup")) {
            startup = "";
        }
        if ((options.valuesOf(fb).size() +
                 (options.has("q") ? 1 : 0) +
                 (options.has("s") ? 1 : 0) +
                 (options.has("v") ? 1 : 0)) > 1) {
            startmsg("jshell.err.opt.feedback.one");
            return null;
        } else if (options.has(fb)) {
            commandLineFeedbackMode = options.valueOf(fb);
        } else if (options.has("q")) {
            commandLineFeedbackMode = "concise";
        } else if (options.has("s")) {
            commandLineFeedbackMode = "silent";
        } else if (options.has("v")) {
            commandLineFeedbackMode = "verbose";
        }
        if (options.has(r)) {
            remoteVMOptions = options.valuesOf(r);
        }

        return options.valuesOf(loadFileSpec);
    }

    private void printUsage() {
        cmdout.print(getResourceString("help.usage"));
    }

    /**
     * Message handler to use during initial start-up.
     */
    private class InitMessageHandler implements MessageHandler {

        @Override
        public void fluff(String format, Object... args) {
            //ignore
        }

        @Override
        public void fluffmsg(String messageKey, Object... args) {
            //ignore
        }

        @Override
        public void errormsg(String messageKey, Object... args) {
            startmsg(messageKey, args);
        }
    }

    private void resetState() {
        closeState();

        // Initialize tool id mapping
        mainNamespace = new NameSpace("main", "");
        startNamespace = new NameSpace("start", "s");
        errorNamespace = new NameSpace("error", "e");
        mapSnippet = new LinkedHashMap<>();
        currentNameSpace = startNamespace;

        // Reset the replayable history, saving the old for restore
        replayableHistoryPrevious = replayableHistory;
        replayableHistory = new ArrayList<>();

        state = JShell.builder()
                .in(userin)
                .out(userout)
                .err(usererr)
                .tempVariableNameGenerator(()-> "$" + currentNameSpace.tidNext())
                .idGenerator((sn, i) -> (currentNameSpace == startNamespace || state.status(sn).isActive())
                        ? currentNameSpace.tid(sn)
                        : errorNamespace.tid(sn))
                .remoteVMOptions(remoteVMOptions.toArray(new String[remoteVMOptions.size()]))
                .build();
        shutdownSubscription = state.onShutdown((JShell deadState) -> {
            if (deadState == state) {
                hardmsg("jshell.msg.terminated");
                live = false;
            }
        });
        analysis = state.sourceCodeAnalysis();
        live = true;
        if (!feedbackInitialized) {
            // One time per run feedback initialization
            feedbackInitialized = true;
            initFeedback();
        }

        if (cmdlineClasspath != null) {
            state.addToClasspath(cmdlineClasspath);
        }

        startUpRun(startup);
        currentNameSpace = mainNamespace;
    }

    private boolean isRunningInteractive() {
        return currentNameSpace != null && currentNameSpace == mainNamespace;
    }

    //where -- one-time per run initialization of feedback modes
    private void initFeedback() {
        // No fluff, no prefix, for init failures
        MessageHandler initmh = new InitMessageHandler();
        // Execute the feedback initialization code in the resource file
        startUpRun(getResourceString("startup.feedback"));
        // These predefined modes are read-only
        feedback.markModesReadOnly();
        // Restore user defined modes retained on previous run with /retain mode
        String encoded = prefs.get(MODE_KEY, null);
        if (encoded != null && !encoded.isEmpty()) {
            if (!feedback.restoreEncodedModes(initmh, encoded)) {
                // Catastrophic corruption -- remove the retained modes
                prefs.remove(MODE_KEY);
            }
        }
        if (commandLineFeedbackMode != null) {
            // The feedback mode to use was specified on the command line, use it
            if (!feedback.setFeedback(initmh, new ArgTokenizer("--feedback", commandLineFeedbackMode))) {
                regenerateOnDeath = false;
            }
            commandLineFeedbackMode = null;
        } else {
            String fb = prefs.get(FEEDBACK_KEY, null);
            if (fb != null) {
                // Restore the feedback mode to use that was retained
                // on a previous run with /retain feedback
                feedback.retainFeedback(initmh, new ArgTokenizer("/retain feedback", fb));
            }
        }
    }

    //where
    private void startUpRun(String start) {
        try (IOContext suin = new FileScannerIOContext(new StringReader(start))) {
            run(suin);
        } catch (Exception ex) {
            hardmsg("jshell.err.startup.unexpected.exception", ex);
            ex.printStackTrace(cmdout);
        }
    }

    private void closeState() {
        live = false;
        JShell oldState = state;
        if (oldState != null) {
            oldState.unsubscribe(shutdownSubscription); // No notification
            oldState.close();
        }
    }

    /**
     * Main loop
     * @param in the line input/editing context
     */
    private void run(IOContext in) {
        IOContext oldInput = input;
        input = in;
        try {
            String incomplete = "";
            while (live) {
                String prompt;
                if (isRunningInteractive()) {
                    prompt = testPrompt
                                    ? incomplete.isEmpty()
                                            ? "\u0005" //ENQ
                                            : "\u0006" //ACK
                                    : incomplete.isEmpty()
                                            ? feedback.getPrompt(currentNameSpace.tidNext())
                                            : feedback.getContinuationPrompt(currentNameSpace.tidNext())
                    ;
                } else {
                    prompt = "";
                }
                String raw;
                try {
                    raw = in.readLine(prompt, incomplete);
                } catch (InputInterruptedException ex) {
                    //input interrupted - clearing current state
                    incomplete = "";
                    continue;
                }
                if (raw == null) {
                    //EOF
                    if (in.interactiveOutput()) {
                        // End after user ctrl-D
                        regenerateOnDeath = false;
                    }
                    break;
                }
                String trimmed = trimEnd(raw);
                if (!trimmed.isEmpty()) {
                    String line = incomplete + trimmed;

                    // No commands in the middle of unprocessed source
                    if (incomplete.isEmpty() && line.startsWith("/") && !line.startsWith("//") && !line.startsWith("/*")) {
                        processCommand(line.trim());
                    } else {
                        incomplete = processSourceCatchingReset(line);
                    }
                }
            }
        } catch (IOException ex) {
            errormsg("jshell.err.unexpected.exception", ex);
        } finally {
            input = oldInput;
        }
    }

    private void addToReplayHistory(String s) {
        if (isRunningInteractive()) {
            replayableHistory.add(s);
        }
    }

    private String processSourceCatchingReset(String src) {
        try {
            input.beforeUserCode();
            return processSource(src);
        } catch (IllegalStateException ex) {
            hard("Resetting...");
            live = false; // Make double sure
            return "";
        } finally {
            input.afterUserCode();
        }
    }

    private void processCommand(String cmd) {
        if (cmd.startsWith("/-")) {
            try {
                //handle "/-[number]"
                cmdUseHistoryEntry(Integer.parseInt(cmd.substring(1)));
                return ;
            } catch (NumberFormatException ex) {
                //ignore
            }
        }
        String arg = "";
        int idx = cmd.indexOf(' ');
        if (idx > 0) {
            arg = cmd.substring(idx + 1).trim();
            cmd = cmd.substring(0, idx);
        }
        Command[] candidates = findCommand(cmd, c -> c.kind.isRealCommand);
        switch (candidates.length) {
            case 0:
                if (!rerunHistoryEntryById(cmd.substring(1))) {
                    errormsg("jshell.err.no.such.command.or.snippet.id", cmd);
                    fluffmsg("jshell.msg.help.for.help");
                }   break;
            case 1:
                Command command = candidates[0];
                // If comand was successful and is of a replayable kind, add it the replayable history
                if (command.run.apply(arg) && command.kind == CommandKind.REPLAY) {
                    addToReplayHistory((command.command + " " + arg).trim());
                }   break;
            default:
                errormsg("jshell.err.command.ambiguous", cmd,
                        Arrays.stream(candidates).map(c -> c.command).collect(Collectors.joining(", ")));
                fluffmsg("jshell.msg.help.for.help");
                break;
        }
    }

    private Command[] findCommand(String cmd, Predicate<Command> filter) {
        Command exact = commands.get(cmd);
        if (exact != null)
            return new Command[] {exact};

        return commands.values()
                       .stream()
                       .filter(filter)
                       .filter(command -> command.command.startsWith(cmd))
                       .toArray(size -> new Command[size]);
    }

    private static Path toPathResolvingUserHome(String pathString) {
        if (pathString.replace(File.separatorChar, '/').startsWith("~/"))
            return Paths.get(System.getProperty("user.home"), pathString.substring(2));
        else
            return Paths.get(pathString);
    }

    static final class Command {
        public final String command;
        public final String helpKey;
        public final Function<String,Boolean> run;
        public final CompletionProvider completions;
        public final CommandKind kind;

        // NORMAL Commands
        public Command(String command, Function<String,Boolean> run, CompletionProvider completions) {
            this(command, run, completions, CommandKind.NORMAL);
        }

        // Special kinds of Commands
        public Command(String command, Function<String,Boolean> run, CompletionProvider completions, CommandKind kind) {
            this(command, "help." + command.substring(1),
                    run, completions, kind);
        }

        // Documentation pseudo-commands
        public Command(String command, String helpKey, CommandKind kind) {
            this(command, helpKey,
                    arg -> { throw new IllegalStateException(); },
                    EMPTY_COMPLETION_PROVIDER,
                    kind);
        }

        public Command(String command, String helpKey, Function<String,Boolean> run, CompletionProvider completions, CommandKind kind) {
            this.command = command;
            this.helpKey = helpKey;
            this.run = run;
            this.completions = completions;
            this.kind = kind;
        }

    }

    interface CompletionProvider {
        List<Suggestion> completionSuggestions(String input, int cursor, int[] anchor);

    }

    enum CommandKind {
        NORMAL(true, true, true),
        REPLAY(true, true, true),
        HIDDEN(true, false, false),
        HELP_ONLY(false, true, false),
        HELP_SUBJECT(false, false, false);

        final boolean isRealCommand;
        final boolean showInHelp;
        final boolean shouldSuggestCompletions;
        private CommandKind(boolean isRealCommand, boolean showInHelp, boolean shouldSuggestCompletions) {
            this.isRealCommand = isRealCommand;
            this.showInHelp = showInHelp;
            this.shouldSuggestCompletions = shouldSuggestCompletions;
        }
    }

    static final class FixedCompletionProvider implements CompletionProvider {

        private final String[] alternatives;

        public FixedCompletionProvider(String... alternatives) {
            this.alternatives = alternatives;
        }

        @Override
        public List<Suggestion> completionSuggestions(String input, int cursor, int[] anchor) {
            List<Suggestion> result = new ArrayList<>();

            for (String alternative : alternatives) {
                if (alternative.startsWith(input)) {
                    result.add(new ArgSuggestion(alternative));
                }
            }

            anchor[0] = 0;

            return result;
        }

    }

    static final CompletionProvider EMPTY_COMPLETION_PROVIDER = new FixedCompletionProvider();
    private static final CompletionProvider KEYWORD_COMPLETION_PROVIDER = new FixedCompletionProvider("-all ", "-start ", "-history ");
    private static final CompletionProvider RELOAD_OPTIONS_COMPLETION_PROVIDER = new FixedCompletionProvider("-restore", "-quiet");
    private static final CompletionProvider SET_MODE_OPTIONS_COMPLETION_PROVIDER = new FixedCompletionProvider("-command", "-quiet", "-delete");
    private static final CompletionProvider FILE_COMPLETION_PROVIDER = fileCompletions(p -> true);
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private void registerCommand(Command cmd) {
        commands.put(cmd.command, cmd);
    }

    private static CompletionProvider skipWordThenCompletion(CompletionProvider completionProvider) {
        return (input, cursor, anchor) -> {
            List<Suggestion> result = Collections.emptyList();

            int space = input.indexOf(' ');
            if (space != -1) {
                String rest = input.substring(space + 1);
                result = completionProvider.completionSuggestions(rest, cursor - space - 1, anchor);
                anchor[0] += space + 1;
            }

            return result;
        };
    }

    private static CompletionProvider fileCompletions(Predicate<Path> accept) {
        return (code, cursor, anchor) -> {
            int lastSlash = code.lastIndexOf('/');
            String path = code.substring(0, lastSlash + 1);
            String prefix = lastSlash != (-1) ? code.substring(lastSlash + 1) : code;
            Path current = toPathResolvingUserHome(path);
            List<Suggestion> result = new ArrayList<>();
            try (Stream<Path> dir = Files.list(current)) {
                dir.filter(f -> accept.test(f) && f.getFileName().toString().startsWith(prefix))
                   .map(f -> new ArgSuggestion(f.getFileName() + (Files.isDirectory(f) ? "/" : "")))
                   .forEach(result::add);
            } catch (IOException ex) {
                //ignore...
            }
            if (path.isEmpty()) {
                StreamSupport.stream(FileSystems.getDefault().getRootDirectories().spliterator(), false)
                             .filter(root -> accept.test(root) && root.toString().startsWith(prefix))
                             .map(root -> new ArgSuggestion(root.toString()))
                             .forEach(result::add);
            }
            anchor[0] = path.length();
            return result;
        };
    }

    private static CompletionProvider classPathCompletion() {
        return fileCompletions(p -> Files.isDirectory(p) ||
                                    p.getFileName().toString().endsWith(".zip") ||
                                    p.getFileName().toString().endsWith(".jar"));
    }

    private CompletionProvider snippetCompletion(Supplier<Stream<? extends Snippet>> snippetsSupplier) {
        return (prefix, cursor, anchor) -> {
            anchor[0] = 0;
            return snippetsSupplier.get()
                        .flatMap(k -> (k instanceof DeclarationSnippet)
                                ? Stream.of(String.valueOf(k.id()), ((DeclarationSnippet) k).name())
                                : Stream.of(String.valueOf(k.id())))
                        .filter(k -> k.startsWith(prefix))
                        .map(k -> new ArgSuggestion(k))
                        .collect(Collectors.toList());
        };
    }

    private CompletionProvider snippetKeywordCompletion(Supplier<Stream<? extends Snippet>> snippetsSupplier) {
        return (code, cursor, anchor) -> {
            List<Suggestion> result = new ArrayList<>();
            result.addAll(KEYWORD_COMPLETION_PROVIDER.completionSuggestions(code, cursor, anchor));
            result.addAll(snippetCompletion(snippetsSupplier).completionSuggestions(code, cursor, anchor));
            return result;
        };
    }

    private static CompletionProvider saveCompletion() {
        return (code, cursor, anchor) -> {
            List<Suggestion> result = new ArrayList<>();
            int space = code.indexOf(' ');
            if (space == (-1)) {
                result.addAll(KEYWORD_COMPLETION_PROVIDER.completionSuggestions(code, cursor, anchor));
            }
            result.addAll(FILE_COMPLETION_PROVIDER.completionSuggestions(code.substring(space + 1), cursor - space - 1, anchor));
            anchor[0] += space + 1;
            return result;
        };
    }

    private static CompletionProvider reloadCompletion() {
        return (code, cursor, anchor) -> {
            List<Suggestion> result = new ArrayList<>();
            int pastSpace = code.indexOf(' ') + 1; // zero if no space
            result.addAll(RELOAD_OPTIONS_COMPLETION_PROVIDER.completionSuggestions(code.substring(pastSpace), cursor - pastSpace, anchor));
            anchor[0] += pastSpace;
            return result;
        };
    }

    private static CompletionProvider orMostSpecificCompletion(
            CompletionProvider left, CompletionProvider right) {
        return (code, cursor, anchor) -> {
            int[] leftAnchor = {-1};
            int[] rightAnchor = {-1};

            List<Suggestion> leftSuggestions = left.completionSuggestions(code, cursor, leftAnchor);
            List<Suggestion> rightSuggestions = right.completionSuggestions(code, cursor, rightAnchor);

            List<Suggestion> suggestions = new ArrayList<>();

            if (leftAnchor[0] >= rightAnchor[0]) {
                anchor[0] = leftAnchor[0];
                suggestions.addAll(leftSuggestions);
            }

            if (leftAnchor[0] <= rightAnchor[0]) {
                anchor[0] = rightAnchor[0];
                suggestions.addAll(rightSuggestions);
            }

            return suggestions;
        };
    }

    // Snippet lists

    Stream<Snippet> allSnippets() {
        return state.snippets();
    }

    Stream<PersistentSnippet> dropableSnippets() {
        return state.snippets()
                .filter(sn -> state.status(sn).isActive() && sn instanceof PersistentSnippet)
                .map(sn -> (PersistentSnippet) sn);
    }

    Stream<VarSnippet> allVarSnippets() {
        return state.snippets()
                .filter(sn -> sn.kind() == Snippet.Kind.VAR)
                .map(sn -> (VarSnippet) sn);
    }

    Stream<MethodSnippet> allMethodSnippets() {
        return state.snippets()
                .filter(sn -> sn.kind() == Snippet.Kind.METHOD)
                .map(sn -> (MethodSnippet) sn);
    }

    Stream<TypeDeclSnippet> allTypeSnippets() {
        return state.snippets()
                .filter(sn -> sn.kind() == Snippet.Kind.TYPE_DECL)
                .map(sn -> (TypeDeclSnippet) sn);
    }

    // Table of commands -- with command forms, argument kinds, helpKey message, implementation, ...

    {
        registerCommand(new Command("/list",
                arg -> cmdList(arg),
                snippetKeywordCompletion(this::allSnippets)));
        registerCommand(new Command("/edit",
                arg -> cmdEdit(arg),
                snippetCompletion(this::allSnippets)));
        registerCommand(new Command("/drop",
                arg -> cmdDrop(arg),
                snippetCompletion(this::dropableSnippets),
                CommandKind.REPLAY));
        registerCommand(new Command("/save",
                arg -> cmdSave(arg),
                saveCompletion()));
        registerCommand(new Command("/open",
                arg -> cmdOpen(arg),
                FILE_COMPLETION_PROVIDER));
        registerCommand(new Command("/vars",
                arg -> cmdVars(arg),
                snippetKeywordCompletion(this::allVarSnippets)));
        registerCommand(new Command("/methods",
                arg -> cmdMethods(arg),
                snippetKeywordCompletion(this::allMethodSnippets)));
        registerCommand(new Command("/types",
                arg -> cmdTypes(arg),
                snippetKeywordCompletion(this::allTypeSnippets)));
        registerCommand(new Command("/imports",
                arg -> cmdImports(),
                EMPTY_COMPLETION_PROVIDER));
        registerCommand(new Command("/exit",
                arg -> cmdExit(),
                EMPTY_COMPLETION_PROVIDER));
        registerCommand(new Command("/reset",
                arg -> cmdReset(),
                EMPTY_COMPLETION_PROVIDER));
        registerCommand(new Command("/reload",
                arg -> cmdReload(arg),
                reloadCompletion()));
        registerCommand(new Command("/classpath",
                arg -> cmdClasspath(arg),
                classPathCompletion(),
                CommandKind.REPLAY));
        registerCommand(new Command("/history",
                arg -> cmdHistory(),
                EMPTY_COMPLETION_PROVIDER));
        registerCommand(new Command("/debug",
                arg -> cmdDebug(arg),
                EMPTY_COMPLETION_PROVIDER,
                CommandKind.HIDDEN));
        registerCommand(new Command("/help",
                arg -> cmdHelp(arg),
                EMPTY_COMPLETION_PROVIDER));
        registerCommand(new Command("/set",
                arg -> cmdSet(arg),
                new ContinuousCompletionProvider(Map.of(
                        // need more completion for format for usability
                        "format", feedback.modeCompletions(),
                        "truncation", feedback.modeCompletions(),
                        "feedback", feedback.modeCompletions(),
                        "mode", skipWordThenCompletion(orMostSpecificCompletion(
                                feedback.modeCompletions(SET_MODE_OPTIONS_COMPLETION_PROVIDER),
                                SET_MODE_OPTIONS_COMPLETION_PROVIDER)),
                        "prompt", feedback.modeCompletions(),
                        "editor", fileCompletions(Files::isExecutable),
                        "start", FILE_COMPLETION_PROVIDER),
                        STARTSWITH_MATCHER)));
        registerCommand(new Command("/retain",
                arg -> cmdRetain(arg),
                new ContinuousCompletionProvider(Map.of(
                        "feedback", feedback.modeCompletions(),
                        "mode", feedback.modeCompletions(),
                        "editor", fileCompletions(Files::isExecutable),
                        "start", FILE_COMPLETION_PROVIDER),
                        STARTSWITH_MATCHER)));
        registerCommand(new Command("/?",
                "help.quest",
                arg -> cmdHelp(arg),
                EMPTY_COMPLETION_PROVIDER,
                CommandKind.NORMAL));
        registerCommand(new Command("/!",
                "help.bang",
                arg -> cmdUseHistoryEntry(-1),
                EMPTY_COMPLETION_PROVIDER,
                CommandKind.NORMAL));

        // Documentation pseudo-commands
        registerCommand(new Command("/<id>",
                "help.id",
                CommandKind.HELP_ONLY));
        registerCommand(new Command("/-<n>",
                "help.previous",
                CommandKind.HELP_ONLY));
        registerCommand(new Command("intro",
                "help.intro",
                CommandKind.HELP_SUBJECT));
        registerCommand(new Command("shortcuts",
                "help.shortcuts",
                CommandKind.HELP_SUBJECT));

        commandCompletions = new ContinuousCompletionProvider(
                commands.values().stream()
                        .filter(c -> c.kind.shouldSuggestCompletions)
                        .collect(toMap(c -> c.command, c -> c.completions)),
                STARTSWITH_MATCHER);
    }

    private ContinuousCompletionProvider commandCompletions;

    public List<Suggestion> commandCompletionSuggestions(String code, int cursor, int[] anchor) {
        return commandCompletions.completionSuggestions(code, cursor, anchor);
    }

    public String commandDocumentation(String code, int cursor) {
        code = code.substring(0, cursor);
        int space = code.indexOf(' ');

        if (space != (-1)) {
            String cmd = code.substring(0, space);
            Command command = commands.get(cmd);
            if (command != null) {
                return getResourceString(command.helpKey + ".summary");
            }
        }

        return null;
    }

    // --- Command implementations ---

    private static final String[] SET_SUBCOMMANDS = new String[]{
        "format", "truncation", "feedback", "mode", "prompt", "editor", "start"};

    private static final String[] RETAIN_SUBCOMMANDS = new String[]{
        "feedback", "mode", "editor", "start"};

    final boolean cmdSet(String arg) {
        String cmd = "/set";
        ArgTokenizer at = new ArgTokenizer(cmd, arg.trim());
        String which = subCommand(cmd, at, SET_SUBCOMMANDS);
        if (which == null) {
            return false;
        }
        switch (which) {
            case "format":
                return feedback.setFormat(this, at);
            case "truncation":
                return feedback.setTruncation(this, at);
            case "feedback":
                return feedback.setFeedback(this, at);
            case "mode":
                return feedback.setMode(this, at);
            case "prompt":
                return feedback.setPrompt(this, at);
            case "editor":
                return setEditor(at, true);
            case "start":
                return setStart(cmd, at, true);
            default:
                errormsg("jshell.err.arg", cmd, at.val());
                return false;
        }
    }

    final boolean cmdRetain(String arg) {
        String cmd = "/retain";
        ArgTokenizer at = new ArgTokenizer(cmd, arg.trim());
        String which = subCommand(cmd, at, RETAIN_SUBCOMMANDS);
        if (which == null) {
            return false;
        }
        switch (which) {
            case "feedback": {
                String fb = feedback.retainFeedback(this, at);
                if (fb != null) {
                    // If a feedback mode has been set now, or in the past, retain it
                    prefs.put(FEEDBACK_KEY, fb);
                    return true;
                }
                return false;
            }
            case "mode":
                String retained = feedback.retainMode(this, at);
                if (retained != null) {
                    // Retain this mode and all previously retained modes
                    prefs.put(MODE_KEY, retained);
                    return true;
                }
                return false;
            case "editor":
                if (!setEditor(at, false)) {
                    return false;
                }
                // retain editor setting
                prefs.put(EDITOR_KEY, (editor == null)
                        ? ""
                        : (editorWait? "-" : "*") + String.join(RECORD_SEPARATOR, editor));
                return true;
            case "start": {
                if (!setStart(cmd, at, false)) {
                    return false;
                }
                // retain startup setting
                prefs.put(STARTUP_KEY, startup);
                return true;
            }
            default:
                errormsg("jshell.err.arg", cmd, at.val());
                return false;
        }
    }

    // Print the help doc for the specified sub-command
    boolean printSubCommandHelp(String cmd, ArgTokenizer at, String helpPrefix, String[] subs) {
        String which = subCommand(cmd, at, subs);
        if (which == null) {
            return false;
        }
        hardrb(helpPrefix + which);
        return true;
    }

    // Find which, if any, sub-command matches
    String subCommand(String cmd, ArgTokenizer at, String[] subs) {
        String[] matches = at.next(subs);
        if (matches == null) {
            // No sub-command was given
            errormsg("jshell.err.sub.arg", cmd);
            return null;
        }
        if (matches.length == 0) {
            // There are no matching sub-commands
            errormsg("jshell.err.arg", cmd, at.val());
            fluffmsg("jshell.msg.use.one.of", Arrays.stream(subs)
                    .collect(Collectors.joining(", "))
            );
            return null;
        }
        if (matches.length > 1) {
            // More than one sub-command matches the initial characters provided
            errormsg("jshell.err.sub.ambiguous", cmd, at.val());
            fluffmsg("jshell.msg.use.one.of", Arrays.stream(matches)
                    .collect(Collectors.joining(", "))
            );
            return null;
        }
        return matches[0];
    }

    // The sub-command:  /set editor <editor-command-line>>
    boolean setEditor(ArgTokenizer at, boolean argsRequired) {
        at.allowedOptions("-default", "-wait");
        String prog = at.next();
        List<String> ed = new ArrayList<>();
        while (at.val() != null) {
            ed.add(at.val());
            at.nextToken();
        }
        if (!checkOptionsAndRemainingInput(at)) {
            return false;
        }
        boolean defaultOption = at.hasOption("-default");
        boolean waitOption = at.hasOption("-wait");
        if (prog != null) {
            if (defaultOption) {
                errormsg("jshell.err.default.option.or.program", at.whole());
                return false;
            }
            editor = ed.toArray(new String[ed.size()]);
            editorWait = waitOption;
            fluffmsg("jshell.msg.set.editor.set", prog);
        } else if (defaultOption) {
            if (waitOption) {
                errormsg("jshell.err.wait.applies.to.external.editor", at.whole());
                return false;
            }
            editor = null;
        } else if (argsRequired) {
            errormsg("jshell.err.set.editor.arg");
            return false;
        }
        return true;
    }

    // The sub-command:  /set start <start-file>
    boolean setStart(String cmd, ArgTokenizer at, boolean argsRequired) {
        at.allowedOptions("-default", "-none");
        String fn = at.next();
        if (!checkOptionsAndRemainingInput(at)) {
            return false;
        }
        int argCount = at.optionCount() + ((fn != null) ? 1 : 0);
        if (argCount > 1 || argsRequired && argCount == 0) {
            errormsg("jshell.err.option.or.filename", at.whole());
            return false;
        }
        if (fn != null) {
            String init = readFile(fn, cmd + " start");
            if (init == null) {
                return false;
            } else {
                startup = init;
                return true;
            }
        } else if (at.hasOption("-default")) {
            startup = DEFAULT_STARTUP;
        } else if (at.hasOption("-none")) {
            startup = "";
        }
        return true;
    }

    boolean cmdClasspath(String arg) {
        if (arg.isEmpty()) {
            errormsg("jshell.err.classpath.arg");
            return false;
        } else {
            state.addToClasspath(toPathResolvingUserHome(arg).toString());
            fluffmsg("jshell.msg.classpath", arg);
            return true;
        }
    }

    boolean cmdDebug(String arg) {
        if (arg.isEmpty()) {
            debug = !debug;
            InternalDebugControl.setDebugFlags(state, debug ? DBG_GEN : 0);
            fluff("Debugging %s", debug ? "on" : "off");
        } else {
            int flags = 0;
            for (char ch : arg.toCharArray()) {
                switch (ch) {
                    case '0':
                        flags = 0;
                        debug = false;
                        fluff("Debugging off");
                        break;
                    case 'r':
                        debug = true;
                        fluff("REPL tool debugging on");
                        break;
                    case 'g':
                        flags |= DBG_GEN;
                        fluff("General debugging on");
                        break;
                    case 'f':
                        flags |= DBG_FMGR;
                        fluff("File manager debugging on");
                        break;
                    case 'c':
                        flags |= DBG_COMPA;
                        fluff("Completion analysis debugging on");
                        break;
                    case 'd':
                        flags |= DBG_DEP;
                        fluff("Dependency debugging on");
                        break;
                    case 'e':
                        flags |= DBG_EVNT;
                        fluff("Event debugging on");
                        break;
                    default:
                        hard("Unknown debugging option: %c", ch);
                        fluff("Use: 0 r g f c d");
                        return false;
                }
            }
            InternalDebugControl.setDebugFlags(state, flags);
        }
        return true;
    }

    private boolean cmdExit() {
        regenerateOnDeath = false;
        live = false;
        if (!replayableHistory.isEmpty()) {
            // Prevent history overflow by calculating what will fit, starting
            // with most recent
            int sepLen = RECORD_SEPARATOR.length();
            int length = 0;
            int first = replayableHistory.size();
            while(length < Preferences.MAX_VALUE_LENGTH && --first >= 0) {
                length += replayableHistory.get(first).length() + sepLen;
            }
            String hist =  String.join(RECORD_SEPARATOR,
                    replayableHistory.subList(first + 1, replayableHistory.size()));
            prefs.put(REPLAY_RESTORE_KEY, hist);
        }
        fluffmsg("jshell.msg.goodbye");
        return true;
    }

    boolean cmdHelp(String arg) {
        ArgTokenizer at = new ArgTokenizer("/help", arg);
        String subject = at.next();
        if (subject != null) {
            Command[] matches = commands.values().stream()
                    .filter(c -> c.command.startsWith(subject))
                    .toArray(size -> new Command[size]);
            at.mark();
            String sub = at.next();
            if (sub != null && matches.length == 1) {
                String cmd = matches[0].command;
                switch (cmd) {
                    case "/set":
                        at.rewind();
                        return printSubCommandHelp(cmd, at, "help.set.", SET_SUBCOMMANDS);
                    case "/retain":
                        at.rewind();
                        return printSubCommandHelp(cmd, at, "help.retain.", RETAIN_SUBCOMMANDS);
                }
            }
            if (matches.length > 0) {
                for (Command c : matches) {
                    hard("");
                    hard("%s", c.command);
                    hard("");
                    hardrb(c.helpKey);
                }
                return true;
            } else {
                errormsg("jshell.err.help.arg", arg);
            }
        }
        hardmsg("jshell.msg.help.begin");
        hardPairs(commands.values().stream()
                .filter(cmd -> cmd.kind.showInHelp),
                cmd -> cmd.command + " " + getResourceString(cmd.helpKey + ".args"),
                cmd -> getResourceString(cmd.helpKey + ".summary")
        );
        hardmsg("jshell.msg.help.subject");
        hardPairs(commands.values().stream()
                .filter(cmd -> cmd.kind == CommandKind.HELP_SUBJECT),
                cmd -> cmd.command,
                cmd -> getResourceString(cmd.helpKey + ".summary")
        );
        return true;
    }

    private boolean cmdHistory() {
        cmdout.println();
        for (String s : input.currentSessionHistory()) {
            // No number prefix, confusing with snippet ids
            cmdout.printf("%s\n", s);
        }
        return true;
    }

    /**
     * Avoid parameterized varargs possible heap pollution warning.
     */
    private interface SnippetPredicate<T extends Snippet> extends Predicate<T> { }

    /**
     * Apply filters to a stream until one that is non-empty is found.
     * Adapted from Stuart Marks
     *
     * @param supplier Supply the Snippet stream to filter
     * @param filters Filters to attempt
     * @return The non-empty filtered Stream, or null
     */
    @SafeVarargs
    private static <T extends Snippet> Stream<T> nonEmptyStream(Supplier<Stream<T>> supplier,
            SnippetPredicate<T>... filters) {
        for (SnippetPredicate<T> filt : filters) {
            Iterator<T> iterator = supplier.get().filter(filt).iterator();
            if (iterator.hasNext()) {
                return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
            }
        }
        return null;
    }

    private boolean inStartUp(Snippet sn) {
        return mapSnippet.get(sn).space == startNamespace;
    }

    private boolean isActive(Snippet sn) {
        return state.status(sn).isActive();
    }

    private boolean mainActive(Snippet sn) {
        return !inStartUp(sn) && isActive(sn);
    }

    private boolean matchingDeclaration(Snippet sn, String name) {
        return sn instanceof DeclarationSnippet
                && ((DeclarationSnippet) sn).name().equals(name);
    }

    /**
     * Convert user arguments to a Stream of snippets referenced by those
     * arguments (or lack of arguments).
     *
     * @param snippets the base list of possible snippets
     * @param defFilter the filter to apply to the arguments if no argument
     * @param rawargs the user's argument to the command, maybe be the empty
     * string
     * @return a Stream of referenced snippets or null if no matches are found
     */
    private <T extends Snippet> Stream<T> argsOptionsToSnippets(Supplier<Stream<T>> snippetSupplier,
            Predicate<Snippet> defFilter, String rawargs, String cmd) {
        ArgTokenizer at = new ArgTokenizer(cmd, rawargs.trim());
        at.allowedOptions("-all", "-start");
        List<String> args = new ArrayList<>();
        String s;
        while ((s = at.next()) != null) {
            args.add(s);
        }
        if (!checkOptionsAndRemainingInput(at)) {
            return null;
        }
        if (at.optionCount() > 0 && args.size() > 0) {
            errormsg("jshell.err.may.not.specify.options.and.snippets", at.whole());
            return null;
        }
        if (at.optionCount() > 1) {
            errormsg("jshell.err.conflicting.options", at.whole());
            return null;
        }
        if (at.hasOption("-all")) {
            // all snippets including start-up, failed, and overwritten
            return snippetSupplier.get();
        }
        if (at.hasOption("-start")) {
            // start-up snippets
            return snippetSupplier.get()
                    .filter(this::inStartUp);
        }
        if (args.isEmpty()) {
            // Default is all active user snippets
            return snippetSupplier.get()
                    .filter(defFilter);
        }
        return argsToSnippets(snippetSupplier, args);
    }

    /**
     * Convert user arguments to a Stream of snippets referenced by those
     * arguments.
     *
     * @param snippetSupplier the base list of possible snippets
     * @param args the user's argument to the command, maybe be the empty list
     * @return a Stream of referenced snippets or null if no matches to specific
     * arg
     */
    private <T extends Snippet> Stream<T> argsToSnippets(Supplier<Stream<T>> snippetSupplier,
            List<String> args) {
        Stream<T> result = null;
        for (String arg : args) {
            // Find the best match
            Stream<T> st = layeredSnippetSearch(snippetSupplier, arg);
            if (st == null) {
                Stream<Snippet> est = layeredSnippetSearch(state::snippets, arg);
                if (est == null) {
                    errormsg("jshell.err.no.such.snippets", arg);
                } else {
                    errormsg("jshell.err.the.snippet.cannot.be.used.with.this.command",
                            arg, est.findFirst().get().source());
                }
                return null;
            }
            if (result == null) {
                result = st;
            } else {
                result = Stream.concat(result, st);
            }
        }
        return result;
    }

    private <T extends Snippet> Stream<T> layeredSnippetSearch(Supplier<Stream<T>> snippetSupplier, String arg) {
        return nonEmptyStream(
                // the stream supplier
                snippetSupplier,
                // look for active user declarations matching the name
                sn -> isActive(sn) && matchingDeclaration(sn, arg),
                // else, look for any declarations matching the name
                sn -> matchingDeclaration(sn, arg),
                // else, look for an id of this name
                sn -> sn.id().equals(arg)
        );
    }

    private boolean cmdDrop(String rawargs) {
        ArgTokenizer at = new ArgTokenizer("/drop", rawargs.trim());
        at.allowedOptions();
        List<String> args = new ArrayList<>();
        String s;
        while ((s = at.next()) != null) {
            args.add(s);
        }
        if (!checkOptionsAndRemainingInput(at)) {
            return false;
        }
        if (args.isEmpty()) {
            errormsg("jshell.err.drop.arg");
            return false;
        }
        Stream<PersistentSnippet> stream = argsToSnippets(this::dropableSnippets, args);
        if (stream == null) {
            // Snippet not found. Error already printed
            fluffmsg("jshell.msg.see.classes.etc");
            return false;
        }
        List<PersistentSnippet> snippets = stream.collect(toList());
        if (snippets.size() > args.size()) {
            // One of the args references more thean one snippet
            errormsg("jshell.err.drop.ambiguous");
            fluffmsg("jshell.msg.use.one.of", snippets.stream()
                    .map(sn -> String.format("\n/drop %-5s :   %s", sn.id(), sn.source().replace("\n", "\n       ")))
                    .collect(Collectors.joining(", "))
            );
            return false;
        }
        snippets.stream()
                .forEach(sn -> state.drop(sn).forEach(this::handleEvent));
        return true;
    }

    private boolean cmdEdit(String arg) {
        Stream<Snippet> stream = argsOptionsToSnippets(state::snippets,
                this::mainActive, arg, "/edit");
        if (stream == null) {
            return false;
        }
        Set<String> srcSet = new LinkedHashSet<>();
        stream.forEachOrdered(sn -> {
            String src = sn.source();
            switch (sn.subKind()) {
                case VAR_VALUE_SUBKIND:
                    break;
                case ASSIGNMENT_SUBKIND:
                case OTHER_EXPRESSION_SUBKIND:
                case TEMP_VAR_EXPRESSION_SUBKIND:
                    if (!src.endsWith(";")) {
                        src = src + ";";
                    }
                    srcSet.add(src);
                    break;
                default:
                    srcSet.add(src);
                    break;
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String s : srcSet) {
            sb.append(s);
            sb.append('\n');
        }
        String src = sb.toString();
        Consumer<String> saveHandler = new SaveHandler(src, srcSet);
        Consumer<String> errorHandler = s -> hard("Edit Error: %s", s);
        if (editor == null) {
            try {
                EditPad.edit(errorHandler, src, saveHandler);
            } catch (RuntimeException ex) {
                errormsg("jshell.err.cant.launch.editor", ex);
                fluffmsg("jshell.msg.try.set.editor");
                return false;
            }
        } else {
            ExternalEditor.edit(editor, errorHandler, src, saveHandler, input,
                    editorWait, this::hardrb);
        }
        return true;
    }
    //where
    // receives editor requests to save
    private class SaveHandler implements Consumer<String> {

        String src;
        Set<String> currSrcs;

        SaveHandler(String src, Set<String> ss) {
            this.src = src;
            this.currSrcs = ss;
        }

        @Override
        public void accept(String s) {
            if (!s.equals(src)) { // quick check first
                src = s;
                try {
                    Set<String> nextSrcs = new LinkedHashSet<>();
                    boolean failed = false;
                    while (true) {
                        CompletionInfo an = analysis.analyzeCompletion(s);
                        if (!an.completeness().isComplete()) {
                            break;
                        }
                        String tsrc = trimNewlines(an.source());
                        if (!failed && !currSrcs.contains(tsrc)) {
                            failed = processCompleteSource(tsrc);
                        }
                        nextSrcs.add(tsrc);
                        if (an.remaining().isEmpty()) {
                            break;
                        }
                        s = an.remaining();
                    }
                    currSrcs = nextSrcs;
                } catch (IllegalStateException ex) {
                    hardmsg("jshell.msg.resetting");
                    resetState();
                    currSrcs = new LinkedHashSet<>(); // re-process everything
                }
            }
        }

        private String trimNewlines(String s) {
            int b = 0;
            while (b < s.length() && s.charAt(b) == '\n') {
                ++b;
            }
            int e = s.length() -1;
            while (e >= 0 && s.charAt(e) == '\n') {
                --e;
            }
            return s.substring(b, e + 1);
        }
    }

    private boolean cmdList(String arg) {
        if (arg.length() >= 2 && "-history".startsWith(arg)) {
            return cmdHistory();
        }
        Stream<Snippet> stream = argsOptionsToSnippets(state::snippets,
                this::mainActive, arg, "/list");
        if (stream == null) {
            return false;
        }

        // prevent double newline on empty list
        boolean[] hasOutput = new boolean[1];
        stream.forEachOrdered(sn -> {
            if (!hasOutput[0]) {
                cmdout.println();
                hasOutput[0] = true;
            }
            cmdout.printf("%4s : %s\n", sn.id(), sn.source().replace("\n", "\n       "));
        });
        return true;
    }

    private boolean cmdOpen(String filename) {
        return runFile(filename, "/open");
    }

    private boolean runFile(String filename, String context) {
        if (!filename.isEmpty()) {
            try {
                run(new FileScannerIOContext(toPathResolvingUserHome(filename).toString()));
                return true;
            } catch (FileNotFoundException e) {
                errormsg("jshell.err.file.not.found", context, filename, e.getMessage());
            } catch (Exception e) {
                errormsg("jshell.err.file.exception", context, filename, e);
            }
        } else {
            errormsg("jshell.err.file.filename", context);
        }
        return false;
    }

    /**
     * Read an external file. Error messages accessed via keyPrefix
     *
     * @param filename file to access or null
     * @param context printable non-natural language context for errors
     * @return contents of file as string
     */
    String readFile(String filename, String context) {
        if (filename != null) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(filename));
                return new String(encoded);
            } catch (AccessDeniedException e) {
                errormsg("jshell.err.file.not.accessible", context, filename, e.getMessage());
            } catch (NoSuchFileException e) {
                errormsg("jshell.err.file.not.found", context, filename);
            } catch (Exception e) {
                errormsg("jshell.err.file.exception", context, filename, e);
            }
        } else {
            errormsg("jshell.err.file.filename", context);
        }
        return null;

    }

    private boolean cmdReset() {
        live = false;
        fluffmsg("jshell.msg.resetting.state");
        return true;
    }

    private boolean cmdReload(String rawargs) {
        ArgTokenizer at = new ArgTokenizer("/reload", rawargs.trim());
        at.allowedOptions("-restore", "-quiet");
        if (!checkOptionsAndRemainingInput(at)) {
            return false;
        }
        Iterable<String> history;
        if (at.hasOption("-restore")) {
            if (replayableHistoryPrevious == null) {
                errormsg("jshell.err.reload.no.previous");
                return false;
            }
            history = replayableHistoryPrevious;
            fluffmsg("jshell.err.reload.restarting.previous.state");
        } else {
            history = replayableHistory;
            fluffmsg("jshell.err.reload.restarting.state");
        }
        boolean echo = !at.hasOption("-quiet");
        resetState();
        run(new ReloadIOContext(history,
                echo ? cmdout : null));
        return true;
    }

    private boolean cmdSave(String rawargs) {
        ArgTokenizer at = new ArgTokenizer("/save", rawargs.trim());
        at.allowedOptions("-all", "-start", "-history");
        String filename = at.next();
        if (filename == null) {
            errormsg("jshell.err.file.filename", "/save");
            return false;
        }
        if (!checkOptionsAndRemainingInput(at)) {
            return false;
        }
        if (at.optionCount() > 1) {
            errormsg("jshell.err.conflicting.options", at.whole());
            return false;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(toPathResolvingUserHome(filename),
                Charset.defaultCharset(),
                CREATE, TRUNCATE_EXISTING, WRITE)) {
            if (at.hasOption("-history")) {
                for (String s : input.currentSessionHistory()) {
                    writer.write(s);
                    writer.write("\n");
                }
            } else if (at.hasOption("-start")) {
                writer.append(startup);
            } else {
                String sources = (at.hasOption("-all")
                        ? state.snippets()
                        : state.snippets().filter(this::mainActive))
                        .map(Snippet::source)
                        .collect(Collectors.joining("\n"));
                writer.write(sources);
            }
        } catch (FileNotFoundException e) {
            errormsg("jshell.err.file.not.found", "/save", filename, e.getMessage());
            return false;
        } catch (Exception e) {
            errormsg("jshell.err.file.exception", "/save", filename, e);
            return false;
        }
        return true;
    }

    private boolean cmdVars(String arg) {
        Stream<VarSnippet> stream = argsOptionsToSnippets(this::allVarSnippets,
                this::isActive, arg, "/vars");
        if (stream == null) {
            return false;
        }
        stream.forEachOrdered(vk ->
        {
            String val = state.status(vk) == Status.VALID
                    ? state.varValue(vk)
                    : getResourceString("jshell.msg.vars.not.active");
            hard("  %s %s = %s", vk.typeName(), vk.name(), val);
        });
        return true;
    }

    private boolean cmdMethods(String arg) {
        Stream<MethodSnippet> stream = argsOptionsToSnippets(this::allMethodSnippets,
                this::isActive, arg, "/methods");
        if (stream == null) {
            return false;
        }
        stream.forEachOrdered(mk
                -> hard("  %s %s", mk.name(), mk.signature())
        );
        return true;
    }

    private boolean cmdTypes(String arg) {
        Stream<TypeDeclSnippet> stream = argsOptionsToSnippets(this::allTypeSnippets,
                this::isActive, arg, "/types");
        if (stream == null) {
            return false;
        }
        stream.forEachOrdered(ck
        -> {
            String kind;
            switch (ck.subKind()) {
                case INTERFACE_SUBKIND:
                    kind = "interface";
                    break;
                case CLASS_SUBKIND:
                    kind = "class";
                    break;
                case ENUM_SUBKIND:
                    kind = "enum";
                    break;
                case ANNOTATION_TYPE_SUBKIND:
                    kind = "@interface";
                    break;
                default:
                    assert false : "Wrong kind" + ck.subKind();
                    kind = "class";
                    break;
            }
            hard("  %s %s", kind, ck.name());
        });
        return true;
    }

    private boolean cmdImports() {
        state.imports().forEach(ik -> {
            hard("  import %s%s", ik.isStatic() ? "static " : "", ik.fullname());
        });
        return true;
    }

    private boolean cmdUseHistoryEntry(int index) {
        List<Snippet> keys = state.snippets().collect(toList());
        if (index < 0)
            index += keys.size();
        else
            index--;
        if (index >= 0 && index < keys.size()) {
            rerunSnippet(keys.get(index));
        } else {
            errormsg("jshell.err.out.of.range");
            return false;
        }
        return true;
    }

    boolean checkOptionsAndRemainingInput(ArgTokenizer at) {
        String junk = at.remainder();
        if (!junk.isEmpty()) {
            errormsg("jshell.err.unexpected.at.end", junk, at.whole());
            return false;
        } else {
            String bad = at.badOptions();
            if (!bad.isEmpty()) {
                errormsg("jshell.err.unknown.option", bad, at.whole());
                return false;
            }
        }
        return true;
    }

    private boolean rerunHistoryEntryById(String id) {
        Optional<Snippet> snippet = state.snippets()
            .filter(s -> s.id().equals(id))
            .findFirst();
        return snippet.map(s -> {
            rerunSnippet(s);
            return true;
        }).orElse(false);
    }

    private void rerunSnippet(Snippet snippet) {
        String source = snippet.source();
        cmdout.printf("%s\n", source);
        input.replaceLastHistoryEntry(source);
        processSourceCatchingReset(source);
    }

    /**
     * Filter diagnostics for only errors (no warnings, ...)
     * @param diagnostics input list
     * @return filtered list
     */
    List<Diag> errorsOnly(List<Diag> diagnostics) {
        return diagnostics.stream()
                .filter(d -> d.isError())
                .collect(toList());
    }

    void displayDiagnostics(String source, Diag diag, List<String> toDisplay) {
        for (String line : diag.getMessage(null).split("\\r?\\n")) { // TODO: Internationalize
            if (!line.trim().startsWith("location:")) {
                toDisplay.add(line);
            }
        }

        int pstart = (int) diag.getStartPosition();
        int pend = (int) diag.getEndPosition();
        Matcher m = LINEBREAK.matcher(source);
        int pstartl = 0;
        int pendl = -2;
        while (m.find(pstartl)) {
            pendl = m.start();
            if (pendl >= pstart) {
                break;
            } else {
                pstartl = m.end();
            }
        }
        if (pendl < pstart) {
            pendl = source.length();
        }
        toDisplay.add(source.substring(pstartl, pendl));

        StringBuilder sb = new StringBuilder();
        int start = pstart - pstartl;
        for (int i = 0; i < start; ++i) {
            sb.append(' ');
        }
        sb.append('^');
        boolean multiline = pend > pendl;
        int end = (multiline ? pendl : pend) - pstartl - 1;
        if (end > start) {
            for (int i = start + 1; i < end; ++i) {
                sb.append('-');
            }
            if (multiline) {
                sb.append("-...");
            } else {
                sb.append('^');
            }
        }
        toDisplay.add(sb.toString());

        debug("printDiagnostics start-pos = %d ==> %d -- wrap = %s", diag.getStartPosition(), start, this);
        debug("Code: %s", diag.getCode());
        debug("Pos: %d (%d - %d)", diag.getPosition(),
                diag.getStartPosition(), diag.getEndPosition());
    }

    private String processSource(String srcInput) throws IllegalStateException {
        while (true) {
            CompletionInfo an = analysis.analyzeCompletion(srcInput);
            if (!an.completeness().isComplete()) {
                return an.remaining();
            }
            boolean failed = processCompleteSource(an.source());
            if (failed || an.remaining().isEmpty()) {
                return "";
            }
            srcInput = an.remaining();
        }
    }
    //where
    private boolean processCompleteSource(String source) throws IllegalStateException {
        debug("Compiling: %s", source);
        boolean failed = false;
        boolean isActive = false;
        List<SnippetEvent> events = state.eval(source);
        for (SnippetEvent e : events) {
            // Report the event, recording failure
            failed |= handleEvent(e);

            // If any main snippet is active, this should be replayable
            // also ignore var value queries
            isActive |= e.causeSnippet() == null &&
                    e.status().isActive() &&
                    e.snippet().subKind() != VAR_VALUE_SUBKIND;
        }
        // If this is an active snippet and it didn't cause the backend to die,
        // add it to the replayable history
        if (isActive && live) {
            addToReplayHistory(source);
        }

        return failed;
    }

    // Handle incoming snippet events -- return true on failure
    private boolean handleEvent(SnippetEvent ste) {
        Snippet sn = ste.snippet();
        if (sn == null) {
            debug("Event with null key: %s", ste);
            return false;
        }
        List<Diag> diagnostics = state.diagnostics(sn).collect(toList());
        String source = sn.source();
        if (ste.causeSnippet() == null) {
            // main event
            for (Diag d : diagnostics) {
                hardmsg(d.isError()? "jshell.msg.error" : "jshell.msg.warning");
                List<String> disp = new ArrayList<>();
                displayDiagnostics(source, d, disp);
                disp.stream()
                        .forEach(l -> hard("%s", l));
            }

            if (ste.status() != Status.REJECTED) {
                if (ste.exception() != null) {
                    if (ste.exception() instanceof EvalException) {
                        printEvalException((EvalException) ste.exception());
                        return true;
                    } else if (ste.exception() instanceof UnresolvedReferenceException) {
                        printUnresolvedException((UnresolvedReferenceException) ste.exception());
                    } else {
                        hard("Unexpected execution exception: %s", ste.exception());
                        return true;
                    }
                } else {
                    new DisplayEvent(ste, false, ste.value(), diagnostics).displayDeclarationAndValue();
                }
            } else {
                if (diagnostics.isEmpty()) {
                    errormsg("jshell.err.failed");
                }
                return true;
            }
        } else {
            // Update
            if (sn instanceof DeclarationSnippet) {
                List<Diag> other = errorsOnly(diagnostics);

                // display update information
                new DisplayEvent(ste, true, ste.value(), other).displayDeclarationAndValue();
            }
        }
        return false;
    }
    //where
    void printStackTrace(StackTraceElement[] stes) {
        for (StackTraceElement ste : stes) {
            StringBuilder sb = new StringBuilder();
            String cn = ste.getClassName();
            if (!cn.isEmpty()) {
                int dot = cn.lastIndexOf('.');
                if (dot > 0) {
                    sb.append(cn.substring(dot + 1));
                } else {
                    sb.append(cn);
                }
                sb.append(".");
            }
            if (!ste.getMethodName().isEmpty()) {
                sb.append(ste.getMethodName());
                sb.append(" ");
            }
            String fileName = ste.getFileName();
            int lineNumber = ste.getLineNumber();
            String loc = ste.isNativeMethod()
                    ? getResourceString("jshell.msg.native.method")
                    : fileName == null
                            ? getResourceString("jshell.msg.unknown.source")
                            : lineNumber >= 0
                                    ? fileName + ":" + lineNumber
                                    : fileName;
            hard("      at %s(%s)", sb, loc);

        }
    }
    //where
    void printUnresolvedException(UnresolvedReferenceException ex) {
        DeclarationSnippet corralled =  ex.getSnippet();
        List<Diag> otherErrors = errorsOnly(state.diagnostics(corralled).collect(toList()));
        new DisplayEvent(corralled, state.status(corralled), FormatAction.USED, true, null, otherErrors)
                .displayDeclarationAndValue();
    }
    //where
    void printEvalException(EvalException ex) {
        if (ex.getMessage() == null) {
            hard("%s thrown", ex.getExceptionClassName());
        } else {
            hard("%s thrown: %s", ex.getExceptionClassName(), ex.getMessage());
        }
        printStackTrace(ex.getStackTrace());
    }

    private FormatAction toAction(Status status, Status previousStatus, boolean isSignatureChange) {
        FormatAction act;
        switch (status) {
            case VALID:
            case RECOVERABLE_DEFINED:
            case RECOVERABLE_NOT_DEFINED:
                if (previousStatus.isActive()) {
                    act = isSignatureChange
                            ? FormatAction.REPLACED
                            : FormatAction.MODIFIED;
                } else {
                    act = FormatAction.ADDED;
                }
                break;
            case OVERWRITTEN:
                act = FormatAction.OVERWROTE;
                break;
            case DROPPED:
                act = FormatAction.DROPPED;
                break;
            case REJECTED:
            case NONEXISTENT:
            default:
                // Should not occur
                error("Unexpected status: " + previousStatus.toString() + "=>" + status.toString());
                act = FormatAction.DROPPED;
        }
        return act;
    }

    class DisplayEvent {
        private final Snippet sn;
        private final FormatAction action;
        private final boolean update;
        private final String value;
        private final List<String> errorLines;
        private final FormatResolve resolution;
        private final String unresolved;
        private final FormatUnresolved unrcnt;
        private final FormatErrors errcnt;

        DisplayEvent(SnippetEvent ste, boolean update, String value, List<Diag> errors) {
            this(ste.snippet(), ste.status(), toAction(ste.status(), ste.previousStatus(), ste.isSignatureChange()), update, value, errors);
        }

        DisplayEvent(Snippet sn, Status status, FormatAction action, boolean update, String value, List<Diag> errors) {
            this.sn = sn;
            this.action = action;
            this.update = update;
            this.value = value;
            this.errorLines = new ArrayList<>();
            for (Diag d : errors) {
                displayDiagnostics(sn.source(), d, errorLines);
            }
            long unresolvedCount;
            if (sn instanceof DeclarationSnippet && (status == Status.RECOVERABLE_DEFINED || status == Status.RECOVERABLE_NOT_DEFINED)) {
                resolution = (status == Status.RECOVERABLE_NOT_DEFINED)
                        ? FormatResolve.NOTDEFINED
                        : FormatResolve.DEFINED;
                unresolved = unresolved((DeclarationSnippet) sn);
                unresolvedCount = state.unresolvedDependencies((DeclarationSnippet) sn).count();
            } else {
                resolution = FormatResolve.OK;
                unresolved = "";
                unresolvedCount = 0;
            }
            unrcnt = unresolvedCount == 0
                    ? FormatUnresolved.UNRESOLVED0
                    : unresolvedCount == 1
                        ? FormatUnresolved.UNRESOLVED1
                        : FormatUnresolved.UNRESOLVED2;
            errcnt = errors.isEmpty()
                    ? FormatErrors.ERROR0
                    : errors.size() == 1
                        ? FormatErrors.ERROR1
                        : FormatErrors.ERROR2;
        }

        private String unresolved(DeclarationSnippet key) {
            List<String> unr = state.unresolvedDependencies(key).collect(toList());
            StringBuilder sb = new StringBuilder();
            int fromLast = unr.size();
            if (fromLast > 0) {
                sb.append(" ");
            }
            for (String u : unr) {
                --fromLast;
                sb.append(u);
                switch (fromLast) {
                    // No suffix
                    case 0:
                        break;
                    case 1:
                        sb.append(", and ");
                        break;
                    default:
                        sb.append(", ");
                        break;
                }
            }
            return sb.toString();
        }

        private void custom(FormatCase fcase, String name) {
            custom(fcase, name, null);
        }

        private void custom(FormatCase fcase, String name, String type) {
            String display = feedback.format(fcase, action, (update ? FormatWhen.UPDATE : FormatWhen.PRIMARY),
                    resolution, unrcnt, errcnt,
                    name, type, value, unresolved, errorLines);
            if (interactive()) {
                cmdout.print(display);
            }
        }

        @SuppressWarnings("fallthrough")
        private void displayDeclarationAndValue() {
            switch (sn.subKind()) {
                case CLASS_SUBKIND:
                    custom(FormatCase.CLASS, ((TypeDeclSnippet) sn).name());
                    break;
                case INTERFACE_SUBKIND:
                    custom(FormatCase.INTERFACE, ((TypeDeclSnippet) sn).name());
                    break;
                case ENUM_SUBKIND:
                    custom(FormatCase.ENUM, ((TypeDeclSnippet) sn).name());
                    break;
                case ANNOTATION_TYPE_SUBKIND:
                    custom(FormatCase.ANNOTATION, ((TypeDeclSnippet) sn).name());
                    break;
                case METHOD_SUBKIND:
                    custom(FormatCase.METHOD, ((MethodSnippet) sn).name(), ((MethodSnippet) sn).parameterTypes());
                    break;
                case VAR_DECLARATION_SUBKIND: {
                    VarSnippet vk = (VarSnippet) sn;
                    custom(FormatCase.VARDECL, vk.name(), vk.typeName());
                    break;
                }
                case VAR_DECLARATION_WITH_INITIALIZER_SUBKIND: {
                    VarSnippet vk = (VarSnippet) sn;
                    custom(FormatCase.VARINIT, vk.name(), vk.typeName());
                    break;
                }
                case TEMP_VAR_EXPRESSION_SUBKIND: {
                    VarSnippet vk = (VarSnippet) sn;
                    custom(FormatCase.EXPRESSION, vk.name(), vk.typeName());
                    break;
                }
                case OTHER_EXPRESSION_SUBKIND:
                    error("Unexpected expression form -- value is: %s", (value));
                    break;
                case VAR_VALUE_SUBKIND: {
                    ExpressionSnippet ek = (ExpressionSnippet) sn;
                    custom(FormatCase.VARVALUE, ek.name(), ek.typeName());
                    break;
                }
                case ASSIGNMENT_SUBKIND: {
                    ExpressionSnippet ek = (ExpressionSnippet) sn;
                    custom(FormatCase.ASSIGNMENT, ek.name(), ek.typeName());
                    break;
                }
                case SINGLE_TYPE_IMPORT_SUBKIND:
                case TYPE_IMPORT_ON_DEMAND_SUBKIND:
                case SINGLE_STATIC_IMPORT_SUBKIND:
                case STATIC_IMPORT_ON_DEMAND_SUBKIND:
                    custom(FormatCase.IMPORT, ((ImportSnippet) sn).name());
                    break;
                case STATEMENT_SUBKIND:
                    custom(FormatCase.STATEMENT, null);
                    break;
            }
        }
    }

    /** The current version number as a string.
     */
    String version() {
        return version("release");  // mm.nn.oo[-milestone]
    }

    /** The current full version number as a string.
     */
    String fullVersion() {
        return version("full"); // mm.mm.oo[-milestone]-build
    }

    private String version(String key) {
        if (versionRB == null) {
            try {
                versionRB = ResourceBundle.getBundle(VERSION_RB_NAME, locale);
            } catch (MissingResourceException e) {
                return "(version info not available)";
            }
        }
        try {
            return versionRB.getString(key);
        }
        catch (MissingResourceException e) {
            return "(version info not available)";
        }
    }

    class NameSpace {
        final String spaceName;
        final String prefix;
        private int nextNum;

        NameSpace(String spaceName, String prefix) {
            this.spaceName = spaceName;
            this.prefix = prefix;
            this.nextNum = 1;
        }

        String tid(Snippet sn) {
            String tid = prefix + nextNum++;
            mapSnippet.put(sn, new SnippetInfo(sn, this, tid));
            return tid;
        }

        String tidNext() {
            return prefix + nextNum;
        }
    }

    static class SnippetInfo {
        final Snippet snippet;
        final NameSpace space;
        final String tid;

        SnippetInfo(Snippet snippet, NameSpace space, String tid) {
            this.snippet = snippet;
            this.space = space;
            this.tid = tid;
        }
    }

    static class ArgSuggestion implements Suggestion {

        private final String continuation;

        /**
         * Create a {@code Suggestion} instance.
         *
         * @param continuation a candidate continuation of the user's input
         */
        public ArgSuggestion(String continuation) {
            this.continuation = continuation;
        }

        /**
         * The candidate continuation of the given user's input.
         *
         * @return the continuation string
         */
        @Override
        public String continuation() {
            return continuation;
        }

        /**
         * Indicates whether input continuation matches the target type and is thus
         * more likely to be the desired continuation. A matching continuation is
         * preferred.
         *
         * @return {@code false}, non-types analysis
         */
        @Override
        public boolean matchesType() {
            return false;
        }
    }
}

abstract class NonInteractiveIOContext extends IOContext {

    @Override
    public boolean interactiveOutput() {
        return false;
    }

    @Override
    public Iterable<String> currentSessionHistory() {
        return Collections.emptyList();
    }

    @Override
    public boolean terminalEditorRunning() {
        return false;
    }

    @Override
    public void suspend() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void beforeUserCode() {
    }

    @Override
    public void afterUserCode() {
    }

    @Override
    public void replaceLastHistoryEntry(String source) {
    }
}

class ScannerIOContext extends NonInteractiveIOContext {
    private final Scanner scannerIn;

    ScannerIOContext(Scanner scannerIn) {
        this.scannerIn = scannerIn;
    }

    @Override
    public String readLine(String prompt, String prefix) {
        if (scannerIn.hasNextLine()) {
            return scannerIn.nextLine();
        } else {
            return null;
        }
    }

    @Override
    public void close() {
        scannerIn.close();
    }

    @Override
    public int readUserInput() {
        return -1;
    }
}

class FileScannerIOContext extends ScannerIOContext {

    FileScannerIOContext(String fn) throws FileNotFoundException {
        this(new FileReader(fn));
    }

    FileScannerIOContext(Reader rdr) throws FileNotFoundException {
        super(new Scanner(rdr));
    }
}

class ReloadIOContext extends NonInteractiveIOContext {
    private final Iterator<String> it;
    private final PrintStream echoStream;

    ReloadIOContext(Iterable<String> history, PrintStream echoStream) {
        this.it = history.iterator();
        this.echoStream = echoStream;
    }

    @Override
    public String readLine(String prompt, String prefix) {
        String s = it.hasNext()
                ? it.next()
                : null;
        if (echoStream != null && s != null) {
            String p = "-: ";
            String p2 = "\n   ";
            echoStream.printf("%s%s\n", p, s.replace("\n", p2));
        }
        return s;
    }

    @Override
    public void close() {
    }

    @Override
    public int readUserInput() {
        return -1;
    }
}
