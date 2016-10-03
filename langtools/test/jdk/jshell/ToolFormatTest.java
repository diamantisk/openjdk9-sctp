/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8148316 8148317 8151755 8152246 8153551 8154812 8157261
 * @summary Tests for output customization
 * @library /tools/lib
 * @modules jdk.compiler/com.sun.tools.javac.api
 *          jdk.compiler/com.sun.tools.javac.main
 *          jdk.jdeps/com.sun.tools.javap
 *          jdk.jshell/jdk.internal.jshell.tool
 * @build KullaTesting TestingInputStream toolbox.ToolBox Compiler
 * @run testng ToolFormatTest
 */
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;

@Test
public class ToolFormatTest extends ReplToolTesting {

    public void testSetFormat() {
        try {
            test(
                    (a) -> assertCommandOutputStartsWith(a, "/set mode test -command", "|  Created new feedback mode: test"),
                    (a) -> assertCommand(a, "/set format test pre '$ '", ""),
                    (a) -> assertCommand(a, "/set format test post ''", ""),
                    (a) -> assertCommand(a, "/set format test act 'ADD' added", ""),
                    (a) -> assertCommand(a, "/set format test act 'MOD' modified", ""),
                    (a) -> assertCommand(a, "/set format test act 'REP' replaced", ""),
                    (a) -> assertCommand(a, "/set format test act 'OVR' overwrote", ""),
                    (a) -> assertCommand(a, "/set format test act 'USE' used", ""),
                    (a) -> assertCommand(a, "/set format test act 'DRP' dropped", ""),
                    (a) -> assertCommand(a, "/set format test up 'UP-' update", ""),
                    (a) -> assertCommand(a, "/set format test action '{up}{act} '", ""),
                    (a) -> assertCommand(a, "/set format test resolve 'OK' ok", ""),
                    (a) -> assertCommand(a, "/set format test resolve 'DEF' defined", ""),
                    (a) -> assertCommand(a, "/set format test resolve 'NODEF' notdefined", ""),
                    (a) -> assertCommand(a, "/set format test fname ':{name} ' ", ""),
                    (a) -> assertCommand(a, "/set format test ftype '[{type}]' method,expression", ""),
                    (a) -> assertCommand(a, "/set format test result '={value} ' expression", ""),
                    (a) -> assertCommand(a, "/set format test display '{pre}{action}{ftype}{fname}{result}{resolve}'", ""),
                    (a) -> assertCommand(a, "/set format test display '{pre}HI this is enum' enum", ""),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback test", "$ Feedback mode: test"),
                    (a) -> assertCommand(a, "class D {}", "$ ADD :D OK"),
                    (a) -> assertCommand(a, "void m() {}", "$ ADD []:m OK"),
                    (a) -> assertCommand(a, "interface EX extends EEX {}", "$ ADD :EX NODEF"),
                    (a) -> assertCommand(a, "56", "$ ADD [int]:$4 =56 OK"),
                    (a) -> assertCommand(a, "class D { int hh; }", "$ REP :D OK$ UP-OVR :D OK"),
                    (a) -> assertCommand(a, "enum E {A,B}", "$ HI this is enum"),
                    (a) -> assertCommand(a, "int z() { return f(); }", "$ ADD []:z DEF"),
                    (a) -> assertCommand(a, "z()", "$ UP-USE []:z DEF"),
                    (a) -> assertCommand(a, "/drop z", "$ DRP []:z OK"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback normal", "|  Feedback mode: normal")
            );
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }

    public void testSetFormatSelector() {
        List<ReplTest> tests = new ArrayList<>();
        tests.add((a) -> assertCommandOutputStartsWith(a, "/set mode ate -quiet",
                            "|  Created new feedback mode: ate"));
        tests.add((a) -> assertCommand(a, "/set feedback ate", ""));
        StringBuilder sb = new StringBuilder();
        class KindList {
            final String[] values;
            final int matchIndex;
            int current;
            boolean match;
            KindList(String[] values, int matchIndex) {
                this.values = values;
                this.matchIndex = matchIndex;
                this.current = 1 << values.length;
            }
            boolean next() {
                if (current <= 0) {
                    return false;
                }
                --current;
                return true;
            }
            boolean append(boolean ahead) {
                boolean any = false;
                match = false;
                for (int i = values.length - 1; i >= 0 ; --i) {
                    if ((current & (1 << i)) != 0) {
                        match |= i == matchIndex;
                        if (any) {
                            sb.append(",");
                        } else {
                            if (ahead) {
                                sb.append("-");
                            }
                        }
                        sb.append(values[i]);
                        any = true;
                    }
                }
                match |= !any;
                return ahead || any;
            }
        }
        KindList klcase = new KindList(new String[] {"class", "method", "expression", "vardecl"}, 2);
        while (klcase.next()) {
            KindList klact  = new KindList(new String[] {"added", "modified", "replaced"}, 0);
            while (klact.next()) {
                KindList klwhen = new KindList(new String[] {"update", "primary"}, 1);
                while (klwhen.next()) {
                    sb.setLength(0);
                    klwhen.append(
                        klact.append(
                            klcase.append(false)));
                    boolean match = klcase.match && klact.match && klwhen.match;
                    String select = sb.toString();
                    String yes = "+++" + select + "+++";
                    String no  = "---" + select + "---";
                    String expect = match? yes : no;
                    tests.add((a) -> assertCommand(a, "/set format ate display '" + no  + "'", ""));
                    tests.add((a) -> assertCommand(a, "/set format ate display '" + yes + "' " + select, ""));
                    tests.add((a) -> assertCommand(a, "\"" + select + "\"", expect));
                }
            }
        }
        tests.add((a) -> assertCommandOutputStartsWith(a, "/set feedback normal", "|  Feedback mode: normal"));

        try {
            test(tests.toArray(new ReplTest[tests.size()]));
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }

    public void testSetTruncation() {
        try {
            test(
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback normal", ""),
                    (a) -> assertCommand(a, "String s = java.util.stream.IntStream.range(65, 74)"+
                            ".mapToObj(i -> \"\"+(char)i).reduce((a,b) -> a + b + a).get()",
                            "s ==> \"ABACABADABACABAEABACABADABACABAFABACABADABACABAEABACABADABACABAGABACABADABA ..."),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode test -quiet", ""),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback test", ""),
                    (a) -> assertCommand(a, "/set format test display '{type}:{value}' primary", ""),
                    (a) -> assertCommand(a, "/set truncation test 20", ""),
                    (a) -> assertCommand(a, "/set trunc test 10 varvalue", ""),
                    (a) -> assertCommand(a, "/set trunc test 3 assignment", ""),
                    (a) -> assertCommand(a, "String r = s", "String:\"ABACABADABACABA ..."),
                    (a) -> assertCommand(a, "r", "String:\"ABACA ..."),
                    (a) -> assertCommand(a, "r=s", "String:\"AB")
            );
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }

    public void testDefaultTruncation() {
        test(
                    (a) -> assertCommand(a, "char[] cs = new char[2000];", null),
                    (a) -> assertCommand(a, "Arrays.fill(cs, 'A');", ""),
                    (a) -> assertCommandCheckOutput(a, "String s = new String(cs)",
                            (s) -> {
                                assertTrue(s.length() < 120, "Result too long (" + s.length() + ") -- " + s);
                                assertTrue(s.contains("AAAAAAAAAAAAAAAAAA"), "Bad value: " + s);
                            }),
                    (a) -> assertCommandCheckOutput(a, "s",
                            (s) -> {
                                assertTrue(s.length() > 300, "Result too short (" + s.length() + ") -- " + s);
                                assertTrue(s.contains("AAAAAAAAAAAAAAAAAA"), "Bad value: " + s);
                            }),
                    (a) -> assertCommandCheckOutput(a, "\"X\" + s",
                            (s) -> {
                                assertTrue(s.length() > 300, "Result too short (" + s.length() + ") -- " + s);
                                assertTrue(s.contains("XAAAAAAAAAAAAAAAAAA"), "Bad value: " + s);
                            })
        );
    }

    public void testShowFeedbackModes() {
        test(
                (a) -> assertCommandOutputContains(a, "/set feedback", "normal")
        );
    }

    public void testSetNewModeQuiet() {
        try {
            test(
                    (a) -> assertCommandOutputStartsWith(a, "/set mode nmq -quiet normal", "|  Created new feedback mode: nmq"),
                    (a) -> assertCommand(a, "/set feedback nmq", ""),
                    (a) -> assertCommand(a, "/se mo nmq2 -q nor", ""),
                    (a) -> assertCommand(a, "/se fee nmq2", ""),
                    (a) -> assertCommand(a, "/set mode nmc -command normal", ""),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback nmc", "|  Feedback mode: nmc"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode nm", "|  Created new feedback mode: nm"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback nm", "|  Feedback mode: nm"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback normal", "|  Feedback mode: normal")
            );
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }

    public void testSetError() {
        try {
            test(
                    (a) -> assertCommandOutputStartsWith(a, "/set mode tee -command foo",
                            "|  Does not match any current feedback mode: foo"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode tee flurb",
                            "|  Does not match any current feedback mode: flurb"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode tee",
                            "|  Created new feedback mode: tee"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode verbose",
                            "|  Not valid with a predefined mode: verbose"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode te -command normal",
                            "|  Created new feedback mode: te"),
                    (a) -> assertCommand(a, "/set format te errorpre 'ERROR: '", ""),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback te",
                            ""),
                    (a) -> assertCommandOutputStartsWith(a, "/set ",
                            "ERROR: The '/set' command requires a sub-command"),
                    (a) -> assertCommandOutputStartsWith(a, "/set xyz",
                            "ERROR: Invalid '/set' argument: xyz"),
                    (a) -> assertCommandOutputStartsWith(a, "/set f",
                            "ERROR: Ambiguous sub-command argument to '/set': f"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback",
                            "ERROR: Missing the feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback xyz",
                            "ERROR: Does not match any current feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format",
                            "ERROR: Missing the feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format xyz",
                            "ERROR: Does not match any current feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format t",
                            "ERROR: Matches more then one current feedback mode: t"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te",
                            "ERROR: Missing the field name"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld",
                            "ERROR: Expected format missing"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld aaa",
                            "ERROR: Format 'aaa' must be quoted"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld 'aaa' frog",
                            "ERROR: Not a valid selector"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld 'aaa' import-frog",
                            "ERROR: Not a valid selector"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld 'aaa' import-import",
                            "ERROR: Selector kind in multiple sections of"),
                    (a) -> assertCommandOutputStartsWith(a, "/set format te fld 'aaa' import,added",
                            "ERROR: Different selector kinds in same sections of"),
                    (a) -> assertCommandOutputStartsWith(a, "/set trunc te 20x",
                            "ERROR: Truncation length must be an integer: 20x"),
                    (a) -> assertCommandOutputStartsWith(a, "/set trunc te",
                            "ERROR: Expected truncation length"),
                    (a) -> assertCommandOutputStartsWith(a, "/set truncation te 111 import,added",
                            "ERROR: Different selector kinds in same sections of"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode",
                            "ERROR: Missing the feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set mode x -quiet y",
                            "ERROR: Does not match any current feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt",
                            "ERROR: Missing the feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te",
                            "ERROR: Expected format missing"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te aaa xyz",
                            "ERROR: Format 'aaa' must be quoted"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te 'aaa' xyz",
                            "ERROR: Format 'xyz' must be quoted"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt",
                            "ERROR: Missing the feedback mode"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te",
                            "ERROR: Expected format missing"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te aaa",
                            "ERROR: Format 'aaa' must be quoted"),
                    (a) -> assertCommandOutputStartsWith(a, "/set prompt te 'aaa'",
                            "ERROR: Expected format missing"),
                    (a) -> assertCommandOutputStartsWith(a, "/set feedback normal",
                            "|  Feedback mode: normal")
            );
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }

    public void testSetHelp() {
        try {
            test(
                    (a) -> assertCommandOutputContains(a, "/help /set", "command to launch"),
                    (a) -> assertCommandOutputContains(a, "/help /set format", "display"),
                    (a) -> assertCommandOutputContains(a, "/hel /se for", "vardecl"),
                    (a) -> assertCommandOutputContains(a, "/help /set editor", "temporary file")
            );
        } finally {
            assertCommandCheckOutput(false, "/set feedback normal", s -> {
            });
        }
    }
}
