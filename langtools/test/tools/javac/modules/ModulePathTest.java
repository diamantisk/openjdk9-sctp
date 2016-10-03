/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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
 * @summary tests for --module-path
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.main
 *      jdk.jdeps/com.sun.tools.javap
 *      jdk.jlink/jdk.tools.jmod
 * @build toolbox.ToolBox toolbox.JarTask toolbox.JavacTask toolbox.ModuleBuilder
 *      ModuleTestBase
 * @run main ModulePathTest
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import toolbox.JarTask;
import toolbox.JavacTask;
import toolbox.ModuleBuilder;
import toolbox.Task;
import toolbox.ToolBox;

public class ModulePathTest extends ModuleTestBase {

    public static final String PATH_SEP = File.pathSeparator;

    public static void main(String... args) throws Exception {
        ModulePathTest t = new ModulePathTest();
        t.runTests();
    }

    @Test
    public void testNotExistsOnPath(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", "doesNotExist")
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: --module-path, doesNotExist"))
            throw new Exception("expected output not found");
    }

    @Test
    public void testNotADirOnPath_1(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.txt", "");

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", "dummy.txt")
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: --module-path, dummy.txt"))
            throw new Exception("expected output not found");
    }

    @Test
    public void testNotADirOnPath_2(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.jimage", "");

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", "dummy.jimage")
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.illegal.argument.for.option: --module-path, dummy.jimage"))
            throw new Exception("expected output not found");
    }

    @Test
    public void testExplodedModuleOnPath(Path base) throws Exception {
        Path modSrc = base.resolve("modSrc");
        tb.writeJavaFiles(modSrc,
                "module m1 { exports p; }",
                "package p; public class CC { }");
        Path modClasses = base.resolve("modClasses");
        Files.createDirectories(modClasses);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(modClasses)
                .files(findJavaFiles(modSrc))
                .run()
                .writeAll();

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { requires m1 ; }",
                "class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(classes)
                .options("--module-path", modClasses.toString())
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void testBadExplodedModuleOnPath(Path base) throws Exception {
        Path modClasses = base.resolve("modClasses");
        tb.writeFile(modClasses.resolve("module-info.class"), "module m1 { }");

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { requires m1 ; }",
                "class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(classes)
                .options("-XDrawDiagnostics",
                        "--module-path", modClasses.toString())
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.locn.bad.module-info: " + modClasses.toString()))
            throw new Exception("expected output not found");
    }

    @Test
    public void testAutoJarOnPath(Path base) throws Exception {
        Path jarSrc = base.resolve("jarSrc");
        tb.writeJavaFiles(jarSrc,
                "package p; public class CC { }");
        Path jarClasses = base.resolve("jarClasses");
        Files.createDirectories(jarClasses);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(jarClasses)
                .files(findJavaFiles(jarSrc))
                .run()
                .writeAll();

        Path moduleJar = base.resolve("m1.jar");
        new JarTask(tb, moduleJar)
          .baseDir(jarClasses)
          .files("p/CC.class")
          .run();

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { p.CC cc; }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(classes)
                .options("--module-path", moduleJar.toString(), "--add-modules", "m1")
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void testModJarOnPath(Path base) throws Exception {
        Path jarSrc = base.resolve("jarSrc");
        tb.writeJavaFiles(jarSrc,
                "module m1 { exports p; }",
                "package p; public class CC { }");
        Path jarClasses = base.resolve("jarClasses");
        Files.createDirectories(jarClasses);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(jarClasses)
                .files(findJavaFiles(jarSrc))
                .run()
                .writeAll();

        Path moduleJar = base.resolve("myModule.jar"); // deliberately not m1
        new JarTask(tb, moduleJar)
          .baseDir(jarClasses)
          .files("module-info.class", "p/CC.class")
          .run();

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { requires m1 ; }",
                "class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(classes)
                .options("--module-path", moduleJar.toString())
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void testBadJarOnPath(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.jar", "");

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", "dummy.jar")
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.locn.cant.read.file: dummy.jar"))
            throw new Exception("expected output not found");
    }

    @Test
    public void testJModOnPath(Path base) throws Exception {
        Path jmodSrc = base.resolve("jmodSrc");
        tb.writeJavaFiles(jmodSrc,
                "module m1 { exports p; }",
                "package p; public class CC { }");
        Path jmodClasses = base.resolve("jmodClasses");
        Files.createDirectories(jmodClasses);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(jmodClasses)
                .files(findJavaFiles(jmodSrc))
                .run()
                .writeAll();

        Path jmod = base.resolve("myModule.jmod"); // deliberately not m1
        jmod(jmodClasses, jmod);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                "module m { requires m1 ; }",
                "class C { }");
        Path classes = base.resolve("classes");
        Files.createDirectories(classes);

        new JavacTask(tb, Task.Mode.CMDLINE)
                .outdir(classes)
                .options("--module-path", jmod.toString())
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void testBadJModOnPath(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "class C { }");
        tb.writeFile("dummy.jmod", "");

        String log = new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", "dummy.jmod")
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.contains("- compiler.err.locn.cant.read.file: dummy.jmod"))
            throw new Exception("expected output not found");
    }

    @Test
    public void relativePath(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder(tb, "m1").build(modules);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "module m2 { requires m1; }", "class A { }");

        new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", modules + "/./../modules")
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void duplicatePaths_1(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder(tb, "m1").build(modules);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "module m2 { requires m1; }", "class A { }");

        new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", modules + "/./../modules" + PATH_SEP + modules)
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void duplicatePaths_2(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder(tb, "m1").build(modules);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "module m2 { requires m1; }", "class A { }");

        new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", modules.toString(),
                        "--module-path", modules.toString())
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void oneModuleHidesAnother(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder(tb, "m1")
                .exports("pkg1")
                .classes("package pkg1; public class E { }")
                .build(modules);

        Path deepModuleDirSrc = base.resolve("deepModuleDirSrc");
        Path deepModuleDir = modules.resolve("deepModuleDir");
        new ModuleBuilder(tb, "m1")
                .exports("pkg2")
                .classes("package pkg2; public class E { }")
                .build(deepModuleDirSrc, deepModuleDir);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "module m2 { requires m1; }", " package p; class A { void main() { pkg2.E.class.getName(); } }");

        new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", deepModuleDir + PATH_SEP + modules)
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void modulesInDifferentContainers(Path base) throws Exception {
        Path modules = base.resolve("modules");
        new ModuleBuilder(tb, "m1")
                .exports("one")
                .classes("package one; public class A { }")
                .build(modules);

        new ModuleBuilder(tb, "m2")
                .requires("m1", modules)
                .build(base.resolve("tmp"));
        jar(base.resolve("tmp/m2"), modules.resolve("m2.jar"));

        new ModuleBuilder(tb, "m3")
                .requires("m2", modules)
                .build(base.resolve("tmp"));
        jmod(base.resolve("tmp/m3"), modules.resolve("m3.jmod"));

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, "module m { requires m3; requires m2; requires m1; }",
                "package p; class A { void main() { one.A.class.getName(); } }");

        new JavacTask(tb, Task.Mode.CMDLINE)
                .options("-XDrawDiagnostics",
                        "--module-path", modules.toString())
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    private void jar(Path dir, Path jar) throws IOException {
        new JarTask(tb, jar)
                .baseDir(dir)
                .files(".")
                .run()
                .writeAll();
    }

    private void jmod(Path dir, Path jmod) throws Exception {
        String[] args = {
                "create",
                "--class-path", dir.toString(),
                jmod.toString()
        };
        jdk.tools.jmod.Main.run(args, System.out);
    }
}
